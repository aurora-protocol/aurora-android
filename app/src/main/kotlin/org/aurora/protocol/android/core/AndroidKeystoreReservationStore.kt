package org.aurora.protocol.android.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreReservationStore(context: Context) : ReservationStore {
    private val cipher = AndroidKeystoreReservationCipher()
    private val store = EncryptedReservationStore(
        blobs = AndroidReservationBlobStore(context),
        cipher = cipher,
    )

    override fun save(
        reservation: CoreReservation,
        sourceDigest: ByteArray,
        nowUnix: Long,
        callerSpentHintKeys: List<ByteArray>,
    ) {
        store.save(reservation, sourceDigest, nowUnix, callerSpentHintKeys)
    }

    override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> =
        store.spentHintKeys(sourceDigest, nowUnix)

    override fun load(): CoreReservation? = store.load()

    override fun consume(nowUnix: Long): CoreReservation? = store.consume(nowUnix)

    override fun clear() = store.clear()

    override fun purge() {
        store.purge()
        cipher.clearKey()
    }
}

private class AndroidKeystoreReservationCipher : ReservationCipher {
    private val keyStore = KeyStore.getInstance(keystoreProvider).apply { load(null) }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(associatedData)
            iv = cipher.iv
            require(iv.size == ivBytes) { "unexpected encryption IV" }
            ciphertext = cipher.doFinal(plaintext)
            require(ciphertext.size >= tagBytes) { "invalid encrypted reservation" }
            return ByteArray(envelopeHeaderBytes + ciphertext.size).also { envelope ->
                envelope[0] = envelopeFormat
                envelope[1] = iv.size.toByte()
                System.arraycopy(iv, 0, envelope, envelopeFormatBytes + ivLengthBytes, iv.size)
                System.arraycopy(ciphertext, 0, envelope, envelopeHeaderBytes, ciphertext.size)
            }
        } finally {
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        var iv: ByteArray? = null
        var encryptedPayload: ByteArray? = null
        try {
            require(ciphertext.size >= envelopeHeaderBytes + tagBytes) { "encrypted reservation is truncated" }
            require(ciphertext[0] == envelopeFormat && ciphertext[1].toInt() and 0xff == ivBytes) {
                "encrypted reservation format is invalid"
            }
            iv = ciphertext.copyOfRange(2, envelopeHeaderBytes)
            encryptedPayload = ciphertext.copyOfRange(envelopeHeaderBytes, ciphertext.size)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(tagBits, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(encryptedPayload)
        } finally {
            iv?.fill(0)
            encryptedPayload?.fill(0)
        }
    }

    @Synchronized
    fun clearKey() {
        try {
            keyStore.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // purge() deletes the encrypted blob first, so a stale key retains no
            // reservation data and can be removed by a later explicit purge.
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is SecretKey) {
            return existing
        }
        require(existing == null) { "invalid reservation keystore entry" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keystoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val keyAlias = "org.aurora.protocol.android.reservation"
        const val keystoreProvider = "AndroidKeyStore"
        const val transformation = "AES/GCM/NoPadding"
        const val envelopeFormat: Byte = 1
        const val ivBytes = 12
        const val tagBits = 128
        const val tagBytes = tagBits / Byte.SIZE_BITS
        const val envelopeFormatBytes = 1
        const val ivLengthBytes = 1
        const val envelopeHeaderBytes = envelopeFormatBytes + ivLengthBytes + ivBytes
        val associatedData = "aurora-reservation-store".toByteArray(Charsets.US_ASCII)
    }
}

private class AndroidReservationBlobStore(context: Context) : EncryptedReservationBlobStore {
    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, fileName))

    override fun write(encrypted: ByteArray) = synchronized(processLock) {
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
