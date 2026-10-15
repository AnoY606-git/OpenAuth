package dev.openauth.core.model

import dev.openauth.core.backup.*
import dev.openauth.core.restore.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class HotpCounterHistoryTest {
    private val secret = OtpSecret.fromBytes("12345678901234567890".toByteArray())
    private fun entry(counter: Long = 0) = OtpEntry(UUID(0, 1), "Test", "alice", secret,
        OtpType.HOTP, HashAlgorithm.SHA1, 6, 30, counter, null, false, 0)
    private fun snapshot(vararg entries: OtpEntry) = VaultSnapshot(revision = 0, entries = entries.toList(), groups = emptyList())

    @Test fun historySurvivesDeletionAndNormalizesBothDigitLengths() {
        val issued = HotpCounterHistory.preserve(snapshot(entry(5)))
        val deleted = HotpCounterHistory.preserve(snapshot(), issued)
        val restored = HotpCounterHistory.preserve(snapshot(entry(), entry().copy(id = UUID(0, 2), digits = 8)), deleted)
        assertEquals(listOf(5L, 5L), restored.entries.map { it.nextCounter })
        assertEquals(issued.hotpCounterFloors, deleted.hotpCounterFloors)
        assertEquals(2, deleted.schemaVersion)
        assertEquals(64, deleted.hotpCounterFloors.single().fingerprint.length)
    }

    @Test fun historyIsDefensiveAndStrictlyValidated() {
        val floor = HotpCounterFloor(HotpCounterFloor.fingerprint(secret), 9)
        val input = mutableListOf(floor)
        val snapshot = VaultSnapshot(2, 0, emptyList(), emptyList(), input)
        input.clear()
        assertEquals(listOf(floor), snapshot.hotpCounterFloors)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.hotpCounterFloors as MutableList).clear() }
        for (fingerprint in listOf("bad", "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) { HotpCounterFloor(fingerprint, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { HotpCounterFloor(floor.fingerprint, 0) }
        assertThrows(IllegalArgumentException::class.java) { VaultSnapshot(1, 0, emptyList(), emptyList(), listOf(floor)) }
        assertThrows(IllegalArgumentException::class.java) { VaultSnapshot(2, 0, emptyList(), emptyList(), listOf(floor, floor)) }
    }

    @Test fun schemaOneWithoutHistoryStillRoundTripsWithoutNewFields() {
        val original = snapshot(entry(7))
        val bytes = SnapshotJsonCodec.encode(original)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("hotpCounterFloors"))
        val decoded = SnapshotJsonCodec.decode(bytes)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(original.entries, decoded.entries)
        assertTrue(decoded.hotpCounterFloors.isEmpty())
        assertEquals(7L, HotpCounterHistory.preserve(decoded).hotpCounterFloors.single().nextCounter)
    }

    @Test fun encryptedBackupPreservesHistoryEvenWhenAllAccountsWereDeleted() {
        val deleted = HotpCounterHistory.preserve(snapshot(), snapshot(entry(12)))
        val codec = BackupCodec()
        BackupCredential.password("Synthetic backup password!".toCharArray()).use { password ->
            codec.createGeneration(password).use { generation ->
                val ciphertext = codec.seal(deleted, generation, UUID(0, 3), 123)
                assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(deleted.hotpCounterFloors.single().fingerprint))
                val restored = codec.open(ciphertext, password)
                assertEquals(deleted.hotpCounterFloors, restored.hotpCounterFloors)
                assertEquals(12L, HotpCounterHistory.preserve(snapshot(entry()), restored).entries.single().nextCounter)
            }
        }
    }

    @Test fun bothRestoreModesRetainLocalAndIncomingTombstones() {
        val local = HotpCounterHistory.preserve(snapshot(), snapshot(entry(99)))
        val incoming = snapshot(entry(1).copy(digits = 8))
        for (mode in RestoreMode.entries) {
            val plan = RestorePlanner.plan(local, incoming, mode) { UUID.randomUUID() }
            assertEquals(99L, plan.result.entries.single().nextCounter)
            assertEquals(local.hotpCounterFloors, plan.result.hotpCounterFloors)
            val empty = RestorePlanner.plan(snapshot(), local, mode) { UUID.randomUUID() }
            assertEquals(local.hotpCounterFloors, empty.result.hotpCounterFloors)
        }
    }

    @Test fun historyLimitFailsClosedInsteadOfEvictingOldFloors() {
        fun floor(i: Int) = HotpCounterFloor(i.toString(16).padStart(64, '0'), 1)
        val full = VaultSnapshot(2, 0, emptyList(), emptyList(), List(VaultSnapshot.MAX_HOTP_COUNTER_FLOORS) { floor(it) })
        val incoming = VaultSnapshot(2, 0, emptyList(), emptyList(), listOf(floor(VaultSnapshot.MAX_HOTP_COUNTER_FLOORS)))
        assertThrows(IllegalArgumentException::class.java) { HotpCounterHistory.preserve(incoming, full) }
        assertEquals(VaultSnapshot.MAX_HOTP_COUNTER_FLOORS, full.hotpCounterFloors.size)
    }
}
