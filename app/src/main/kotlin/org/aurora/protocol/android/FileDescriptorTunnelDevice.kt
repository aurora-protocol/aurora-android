package org.aurora.protocol.android

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.aurora.protocol.android.core.TunnelPacketDevice

internal class FileDescriptorTunnelDevice(
    descriptor: ParcelFileDescriptor,
) : TunnelPacketDevice {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val teardown = TunnelDeviceTeardown(descriptor, input, output)
    private val inputBuffer = ByteArray(maximumPacketBytes)
    private val outputLock = Any()

    override fun readPacket(): ByteArray? {
        var readCount: Int? = null
        try {
            val count = input.read(inputBuffer)
            readCount = count
            if (count < 0) {
                return null
            }
            if (count == 0) {
                throw IOException("VPN interface returned an empty packet")
            }
            return inputBuffer.copyOf(count)
        } finally {
            inputBuffer.fill(
                element = 0,
                toIndex = tunnelReadBufferClearBytes(readCount, inputBuffer.size),
            )
        }
    }

    override fun writePacket(packet: ByteArray) = synchronized(outputLock) {
        output.write(packet)
    }

    override fun close() {
        inputBuffer.fill(0)
        teardown.close()
    }

    private companion object {
        const val maximumPacketBytes = 65535
    }
}

internal fun tunnelReadBufferClearBytes(readCount: Int?, capacity: Int): Int {
    require(capacity >= 0) { "invalid tunnel read buffer capacity" }
    return when {
        readCount == null -> capacity
        readCount <= 0 -> 0
        else -> minOf(readCount, capacity)
    }
}
