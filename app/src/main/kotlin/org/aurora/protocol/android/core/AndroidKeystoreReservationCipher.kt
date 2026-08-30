package org.aurora.protocol.android.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreReservationCipher : ReservationCipher {
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
        } catch (error: Exception) {
            AuroraLog.debug("clear keystore key", error)
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
