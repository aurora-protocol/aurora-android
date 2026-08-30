package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeystoreReservationStoreStructureTest {
    @Test
    fun keystoreStoreKeepsCipherAndBlobPersistenceSeparate() {
        val store = storeSource()
        val cipher = cipherSource()
        val blobs = blobSource()

        assertTrue(store.contains("class AndroidKeystoreReservationStore"))
        assertTrue(store.contains("EncryptedReservationStore("))
        assertTrue(cipher.contains("class AndroidKeystoreReservationCipher"))
        assertTrue(cipher.contains("override fun encrypt(plaintext: ByteArray)"))
        assertTrue(blobs.contains("class AndroidReservationBlobStore"))
        assertTrue(blobs.contains("override fun write(encrypted: ByteArray)"))
        assertTrue(blobs.contains("require(encrypted.size in 1..maximumEncryptedBytes)"))
        assertFalse(store.contains("class AndroidKeystoreReservationCipher"))
        assertFalse(store.contains("class AndroidReservationBlobStore"))
        assertFalse(cipher.contains("AtomicFile("))
        assertFalse(blobs.contains("KeyGenParameterSpec"))
        assertTrue(cipher.contains("KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT"))
        assertTrue(cipher.contains(".setKeySize(256)"))
        assertTrue(cipher.contains(".setBlockModes(KeyProperties.BLOCK_MODE_GCM)"))
        assertTrue(cipher.contains(".setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)"))
        assertTrue(cipher.contains(".setRandomizedEncryptionRequired(true)"))
        assertTrue(cipher.contains("const val transformation = \"AES/GCM/NoPadding\""))
        assertTrue(cipher.contains("const val ivBytes = 12"))
        assertTrue(cipher.contains("const val tagBits = 128"))
        assertTrue(blobs.contains("context.noBackupFilesDir"))
        assertTrue(blobs.contains("AtomicFile("))
    }

    private fun storeSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/AndroidKeystoreReservationStore.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/AndroidKeystoreReservationStore.kt",
    )

    private fun cipherSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/AndroidKeystoreReservationCipher.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/AndroidKeystoreReservationCipher.kt",
    )

    private fun blobSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/AndroidReservationBlobStore.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/AndroidReservationBlobStore.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
