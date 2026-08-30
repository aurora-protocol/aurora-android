package org.aurora.protocol.android

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import java.io.FileInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the private FileDescriptorTunnelDevice (see AuroraVpnService.kt) with
 * real OS pipe descriptors, which JVM tests cannot construct.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class FileDescriptorTunnelDeviceInstrumentedTest {
    @Test
    fun writtenPacketArrivesAtTheOtherPipeEnd() {
        val pipe = ParcelFileDescriptor.createPipe()
        val device = newDevice(pipe[1])
        try {
            val packet = ByteArray(128) { it.toByte() }
            device.writePacket(packet)

            val buffer = ByteArray(packet.size * 2)
            // The stream shares the pipe's descriptor; the ParcelFileDescriptor owns closure.
            val count = FileInputStream(pipe[0].fileDescriptor).read(buffer)
            assertEquals(packet.size, count)
            assertArrayEquals(packet, buffer.copyOf(count))
        } finally {
            device.close()
            pipe[0].close()
        }
    }

    @Test
    fun readPacketReturnsTheWrittenBytesAndNullOnEof() {
        val pipe = ParcelFileDescriptor.createPipe()
        val device = newDevice(pipe[0])
        try {
            val packet = ByteArray(64) { (0xff - it).toByte() }
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(packet) }

            assertArrayEquals(packet, device.readPacket())
            assertNull(device.readPacket())
        } finally {
            device.close()
        }
    }

    @Test
    fun closeShutsTheDescriptorAndIsIdempotent() {
        val pipe = ParcelFileDescriptor.createPipe()
        val device = newDevice(pipe[1])
        try {
            device.close()
            device.close()

            // The writer fd is gone, so the reader observes end of stream.
            assertEquals(-1, FileInputStream(pipe[0].fileDescriptor).read())

            val failure = try {
                device.writePacket(byteArrayOf(0x01))
                null
            } catch (error: InvocationTargetException) {
                error.cause
            }
            assertTrue(failure is IOException)
        } finally {
            pipe[0].close()
        }
    }

    @Test
    fun closeUnblocksAReadWaitingOnTheDescriptor() {
        val pipe = ParcelFileDescriptor.createPipe()
        val device = newDevice(pipe[0])
        val executor = Executors.newSingleThreadExecutor()
        val readStarted = CountDownLatch(1)
        val read = executor.submit<ByteArray?> {
            readStarted.countDown()
            device.readPacket()
        }
        try {
            assertTrue(readStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)

            device.close()

            try {
                assertNull(read.get(2, TimeUnit.SECONDS))
            } catch (error: ExecutionException) {
                val invocation = error.cause as? InvocationTargetException
                assertTrue(invocation?.cause is IOException)
            }
        } finally {
            pipe[1].close()
            executor.shutdownNow()
        }
    }

    private fun newDevice(descriptor: ParcelFileDescriptor): FileDescriptorTunnelDeviceHandle {
        val type = Class.forName("org.aurora.protocol.android.FileDescriptorTunnelDevice")
        val constructor = type.getDeclaredConstructor(ParcelFileDescriptor::class.java)
        constructor.isAccessible = true
        return FileDescriptorTunnelDeviceHandle(constructor.newInstance(descriptor))
    }

    private class FileDescriptorTunnelDeviceHandle(private val device: Any) {
        private val type = device.javaClass

        fun readPacket(): ByteArray? = invoke("readPacket") as ByteArray?

        fun writePacket(packet: ByteArray) {
            invoke("writePacket", packet)
        }

        fun close() {
            invoke("close")
        }

        private fun invoke(name: String, vararg arguments: Any?): Any? {
            val parameterTypes = arguments.map { argument ->
                when (argument) {
                    is ByteArray -> ByteArray::class.java
                    else -> error("unsupported argument type")
                }
            }.toTypedArray()
            val method = type.getDeclaredMethod(name, *parameterTypes)
            method.isAccessible = true
            return method.invoke(device, *arguments)
        }
    }
}
