package org.aurora.protocol.android.core

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Roundtrips and tamper handling against the real AndroidKeyStore on device. */
class AndroidKeystoreReservationStoreInstrumentedTest {
    // The instrumented process runs as the app, so only the target context's
    // private storage (and its keystore namespace) is writable.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidKeystoreReservationStore(context)
    private val blobFile = File(context.noBackupFilesDir, "aurora-reservation.bin")

    private val provisioning = ByteArray(97) { (it * 3 + 1).toByte() }
    private val spentHintKey = ByteArray(48) { (it + 7).toByte() }
    private val relayBucketId = ByteArray(16) { (it + 100).toByte() }
    private val sourceDigest = ByteArray(32) { (it + 40).toByte() }

    @Before
    @After
    fun resetStore() {
        store.purge()
    }

    @Test
    fun savesLoadsAndConsumesAReservation() {
        store.save(reservation(), sourceDigest.copyOf(), nowUnix = 1_000)
        // save() takes ownership and zeroes the handed-in reservation.

        store.load().use { loaded ->
            assertNotNull(loaded)
            requireNotNull(loaded)
            assertArrayEquals(provisioning, loaded.provisioning)
            assertArrayEquals(spentHintKey, loaded.spentHintKey)
            assertArrayEquals(relayBucketId, loaded.relayBucketId)
            assertEquals(2_000L, loaded.accessHintExpiryUnix)
        }

        when (val consumption = store.consume(nowUnix = 1_500)) {
            is ReservationConsumption.Available -> {
                assertArrayEquals(provisioning, consumption.reservation.provisioning)
            }
            else -> throw AssertionError("expected available reservation, got $consumption")
        }
        assertNull(store.load())

        // consume() retains the spent-hint history for replay protection.
        val hints = store.spentHintKeys(sourceDigest.copyOf(), nowUnix = 1_500)
        assertEquals(1, hints.size)
        assertTrue(MessageDigest.isEqual(spentHintKey, hints.single()))
    }

    @Test
    fun clearRemovesOnlyTheActiveReservation() {
        store.save(reservation(), sourceDigest.copyOf(), nowUnix = 1_000)
        store.clear()
        assertNull(store.load())
        assertEquals(1, store.spentHintKeys(sourceDigest.copyOf(), nowUnix = 1_500).size)
    }

    @Test
    fun purgeRemovesEverything() {
        store.save(reservation(), sourceDigest.copyOf(), nowUnix = 1_000)
        store.purge()
        assertNull(store.load())
        assertTrue(store.spentHintKeys(sourceDigest.copyOf(), nowUnix = 1_500).isEmpty())
        assertFalse(blobFile.isFile)
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        store.save(reservation(), sourceDigest.copyOf(), nowUnix = 1_000)
        assertTrue(blobFile.isFile)

        val tampered = blobFile.readBytes()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()
        blobFile.writeBytes(tampered)

        assertThrows(ReservationStorageException::class.java) {
            store.load()
        }
        assertThrows(ReservationStorageException::class.java) {
            store.consume(nowUnix = 1_500)
        }
    }

    @Test
    fun garbageBlobFailsClosed() {
        blobFile.parentFile?.mkdirs()
        blobFile.writeBytes(ByteArray(64) { 0x55 })

        assertThrows(ReservationStorageException::class.java) {
            store.load()
        }
    }

    @Test
    fun blobStoreRejectsInvalidSizesWithoutReplacingValidCiphertext() {
        val blobs = AndroidReservationBlobStore(context)
        val valid = ByteArray(64) { (it + 1).toByte() }
        blobs.write(valid)

        assertThrows(IllegalArgumentException::class.java) {
            blobs.write(ByteArray(0))
        }
        assertArrayEquals(valid, blobs.read())

        assertThrows(IllegalArgumentException::class.java) {
            blobs.write(ByteArray((1024 * 1024) + (8 * 1024) + 257))
        }
        assertArrayEquals(valid, blobs.read())
    }

    private fun reservation(): CoreReservation = CoreReservation(
        provisioning = provisioning.copyOf(),
        spentHintKey = spentHintKey.copyOf(),
        relayBucketId = relayBucketId.copyOf(),
        accessHintExpiryUnix = 2_000,
    )
}
