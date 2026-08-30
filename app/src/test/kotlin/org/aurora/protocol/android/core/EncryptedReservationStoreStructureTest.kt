package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedReservationStoreStructureTest {
    @Test
    fun storeKeepsTypesStateAndCodecSeparate() {
        val types = typesSource()
        val store = storeSource()
        val state = stateSource()
        val codec = codecSource()

        assertTrue(types.contains("interface ReservationStore"))
        assertTrue(types.contains("interface ReservationCipher"))
        assertTrue(types.contains("sealed interface ReservationConsumption"))
        assertTrue(store.contains("class EncryptedReservationStore"))
        assertTrue(store.contains("override fun consume(nowUnix: Long)"))
        assertTrue(state.contains("class ReservationStorageState"))
        assertTrue(state.contains("class ReservationHistoryEntry"))
        assertTrue(codec.contains("object ReservationStorageCodec"))
        assertTrue(codec.contains("fun encode(state: ReservationStorageState)"))
        assertTrue(codec.contains("fun decode(encoded: ByteArray)"))
        assertFalse(types.contains("class EncryptedReservationStore"))
        assertFalse(store.contains("object ReservationStorageCodec"))
        assertFalse(store.contains("class ReservationStorageState"))
        assertFalse(codec.contains("class EncryptedReservationStore"))
        assertFalse(state.contains("object ReservationStorageCodec"))
    }

    private fun typesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/EncryptedReservationStoreTypes.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/EncryptedReservationStoreTypes.kt",
    )

    private fun storeSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/EncryptedReservationStore.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/EncryptedReservationStore.kt",
    )

    private fun stateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/ReservationStorageState.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/ReservationStorageState.kt",
    )

    private fun codecSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/ReservationStorageCodec.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/ReservationStorageCodec.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
