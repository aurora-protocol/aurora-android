package org.aurora.protocol.android.core

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal class AndroidReservationBlobStore(context: Context) : EncryptedReservationBlobStore {
    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, fileName))

    override fun write(encrypted: ByteArray) = synchronized(processLock) {
        require(encrypted.size in 1..maximumEncryptedBytes) { "encrypted reservation size is invalid" }
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(encrypted)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(atomicFile::failWrite)
        }
    }

    override fun read(): ByteArray? = synchronized(processLock) {
        if (!atomicFile.baseFile.isFile) {
            return@synchronized null
        }
        atomicFile.openRead().use(::readBounded)
    }

    override fun clear() = synchronized(processLock) {
        atomicFile.delete()
    }

    private fun readBounded(stream: FileInputStream): ByteArray {
        val size = stream.channel.size()
        require(size in 1..maximumEncryptedBytes.toLong()) { "encrypted reservation size is invalid" }
        val buffer = ByteArray(size.toInt())
        var completed = false
        try {
            var offset = 0
            while (offset < buffer.size) {
                val count = stream.read(buffer, offset, buffer.size - offset)
                if (count <= 0) {
                    throw IllegalStateException("encrypted reservation stream did not advance")
                }
                offset += count
            }
            require(stream.read() == -1) { "encrypted reservation size changed during read" }
            completed = true
            return buffer
        } finally {
            if (!completed) {
                buffer.fill(0)
            }
        }
    }

    private companion object {
        const val fileName = "aurora-reservation.bin"
        const val maximumEncryptedBytes = (1024 * 1024) + (8 * 1024) + 256
        val processLock = Any()
    }
}
