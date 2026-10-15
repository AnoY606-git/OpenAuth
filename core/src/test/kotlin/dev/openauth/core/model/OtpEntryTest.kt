package dev.openauth.core.model

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OtpEntryTest {
    @Test
    fun rejectsUnsupportedDigitCounts() {
        for (digits in listOf(0, 5, 7, 9)) {
            assertThrows(IllegalArgumentException::class.java) { entry(digits = digits) }
        }
    }

    @Test
    fun rejectsPeriodsOutsideSupportedRange() {
        for (period in listOf(0, 14, 121)) {
            assertThrows(IllegalArgumentException::class.java) { entry(periodSeconds = period) }
        }
    }

    @Test
    fun acceptsTotpParameterBoundaries() {
        for (algorithm in HashAlgorithm.entries) {
            for (digits in listOf(6, 8)) {
                for (period in listOf(15, 120)) {
                    val result = entry(algorithm = algorithm, digits = digits, periodSeconds = period)
                    assertEquals(algorithm, result.algorithm)
                    assertEquals(digits, result.digits)
                    assertEquals(period, result.periodSeconds)
                }
            }
        }
    }

    @Test
    fun hotpOnlyAcceptsSha1() {
        for (algorithm in listOf(HashAlgorithm.SHA256, HashAlgorithm.SHA512)) {
            assertThrows(IllegalArgumentException::class.java) {
                entry(type = OtpType.HOTP, algorithm = algorithm)
            }
        }
        assertEquals(OtpType.HOTP, entry(type = OtpType.HOTP).type)
    }

    @Test
    fun rejectsNegativeCounters() {
        for (type in OtpType.entries) {
            assertThrows(IllegalArgumentException::class.java) { entry(type = type, nextCounter = -1) }
        }
        assertEquals(Long.MAX_VALUE, entry(type = OtpType.HOTP, nextCounter = Long.MAX_VALUE).nextCounter)
    }

    @Test
    fun rejectsSecretsOutsideSupportedSize() {
        for (size in listOf(0, 9, 129)) {
            assertThrows(IllegalArgumentException::class.java) { OtpSecret.fromBytes(ByteArray(size)) }
        }
        assertEquals(10, OtpSecret.fromBytes(ByteArray(10)).copyBytes().size)
        assertEquals(128, OtpSecret.fromBytes(ByteArray(128)).copyBytes().size)
    }

    @Test
    fun secretOwnsItsBytes() {
        val input = ByteArray(20) { it.toByte() }
        val secret = OtpSecret.fromBytes(input)
        input[0] = 42
        val output = secret.copyBytes()
        output[1] = 42
        assertArrayEquals(ByteArray(20) { it.toByte() }, secret.copyBytes())
    }

    @Test
    fun secretsHaveValueEquality() {
        val left = OtpSecret.fromBytes(ByteArray(20) { it.toByte() })
        val right = OtpSecret.fromBytes(ByteArray(20) { it.toByte() })
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, OtpSecret.fromBytes(ByteArray(20) { 1 }))
    }

    @Test
    fun diagnosticsRedactSecret() {
        val secret = OtpSecret.fromBytes(ByteArray(20))
        val syntheticBase32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertFalse(secret.toString().contains(syntheticBase32))
        assertFalse(secret.toString().contains("0, 0"))
        assertFalse(entry(secret = secret).toString().contains(syntheticBase32))
        assertFalse(entry(secret = secret).toString().contains("0, 0"))
    }

    @Test
    fun rejectsDuplicateEntryIds() {
        val first = entry()
        assertThrows(IllegalArgumentException::class.java) {
            VaultSnapshot(revision = 0, entries = listOf(first, first.copy(issuer = "Other")), groups = emptyList())
        }
    }

    @Test
    fun rejectsDuplicateGroupIds() {
        val first = EntryGroup(GROUP_ID, "Work")
        assertThrows(IllegalArgumentException::class.java) {
            VaultSnapshot(revision = 0, entries = emptyList(), groups = listOf(first, first.copy(name = "Home")))
        }
    }

    @Test
    fun rejectsUnknownGroupReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultSnapshot(revision = 0, entries = listOf(entry(groupId = GROUP_ID)), groups = emptyList())
        }
    }

    @Test
    fun acceptsKnownGroupReferencesAndUngroupedEntries() {
        val snapshot = VaultSnapshot(
            revision = 1,
            entries = listOf(entry(groupId = GROUP_ID), entry(id = SECOND_ID)),
            groups = listOf(EntryGroup(GROUP_ID, "Work")),
        )
        assertEquals(1, snapshot.schemaVersion)
        assertEquals(1L, snapshot.revision)
        assertEquals(2, snapshot.entries.size)
        assertEquals(GROUP_ID, snapshot.entries.first().groupId)
    }

    @Test
    fun snapshotOwnsEntryAndGroupCollections() {
        val originalEntry = entry(groupId = GROUP_ID)
        val originalGroup = EntryGroup(GROUP_ID, "Work")
        val entries = mutableListOf(originalEntry)
        val groups = mutableListOf(originalGroup)
        val snapshot = VaultSnapshot(revision = 0, entries = entries, groups = groups)
        entries.clear()
        groups.clear()
        assertEquals(listOf(originalEntry), snapshot.entries)
        assertEquals(listOf(originalGroup), snapshot.groups)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.entries as MutableList<OtpEntry>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.groups as MutableList<EntryGroup>).clear()
        }
    }

    @Test
    fun rejectsUnsupportedSchemaAndNegativeRevision() {
        for (schema in listOf(0, 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                VaultSnapshot(schemaVersion = schema, revision = 0, entries = emptyList(), groups = emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultSnapshot(revision = -1, entries = emptyList(), groups = emptyList())
        }
    }

    @Test
    fun rejectsSnapshotsAboveEntryLimit() {
        val entries = (0L..10_000L).map { entry(id = UUID(0, it)) }
        assertThrows(IllegalArgumentException::class.java) {
            VaultSnapshot(revision = 0, entries = entries, groups = emptyList())
        }
        assertEquals(10_000, VaultSnapshot(revision = 0, entries = entries.dropLast(1), groups = emptyList()).entries.size)
    }

    private fun entry(
        id: UUID = ENTRY_ID,
        secret: OtpSecret = OtpSecret.fromBytes(ByteArray(20) { it.toByte() }),
        type: OtpType = OtpType.TOTP,
        algorithm: HashAlgorithm = HashAlgorithm.SHA1,
        digits: Int = 6,
        periodSeconds: Int = 30,
        nextCounter: Long = 0,
        groupId: UUID? = null,
    ) = OtpEntry(
        id = id,
        issuer = "Example",
        accountName = "synthetic@example.invalid",
        secret = secret,
        type = type,
        algorithm = algorithm,
        digits = digits,
        periodSeconds = periodSeconds,
        nextCounter = nextCounter,
        groupId = groupId,
        favorite = false,
        sortIndex = 0,
    )

    companion object {
        private val ENTRY_ID = UUID(0, 1)
        private val SECOND_ID = UUID(0, 2)
        private val GROUP_ID = UUID(0, 3)
    }
}
