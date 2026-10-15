package dev.openauth.core.restore

import dev.openauth.core.model.EntryGroup
import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpSecret
import dev.openauth.core.model.OtpType
import dev.openauth.core.model.VaultSnapshot
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestorePlannerTest {
    @Test
    fun sameNamesWithDifferentSecretsKeepBothEntries() {
        val local = entry(1, secret = secret(1))
        val imported = entry(2, secret = secret(2))
        val plan = merge(snapshot(local), snapshot(imported))
        assertEquals(listOf(local, imported), plan.result.entries)
        assertEquals(1, plan.added)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun conflictingEntryIdGetsRemappedWithoutOverwritingLocal() {
        val local = entry(1, secret = secret(1))
        val imported = entry(1, secret = secret(2))
        val plan = merge(snapshot(local), snapshot(imported)) { id(99) }
        assertEquals(listOf(local, imported.copy(id = id(99))), plan.result.entries)
        assertEquals(1, plan.remappedIds)
        assertEquals(1, plan.added)
    }

    @Test
    fun duplicateTotpPreservesEveryLocalMetadataField() {
        val local = entry(1).copy(issuer = "Local", accountName = "local@example.invalid", favorite = true, sortIndex = 7)
        val imported = entry(2).copy(issuer = "Imported", accountName = "imported@example.invalid", sortIndex = 99)
        val plan = merge(snapshot(local), snapshot(imported))
        assertEquals(listOf(local), plan.result.entries)
        assertEquals(0, plan.added)
        assertEquals(1, plan.duplicates)
        assertEquals(0, plan.remappedIds)
    }

    @Test
    fun duplicateHotpUsesHigherImportedCounterAndLocalMetadata() {
        val local = entry(1, type = OtpType.HOTP, nextCounter = 5).copy(favorite = true)
        val imported = entry(2, type = OtpType.HOTP, nextCounter = 9)
        val plan = merge(snapshot(local), snapshot(imported))
        assertEquals(listOf(local.copy(nextCounter = 9)), plan.result.entries)
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(id(1)), plan.hotpWarnings)
        assertEquals(5L, local.nextCounter)
    }

    @Test
    fun duplicateHotpNeverRollsCounterBackOrOverflows() {
        val local = entry(1, type = OtpType.HOTP, nextCounter = Long.MAX_VALUE)
        val imported = entry(2, type = OtpType.HOTP, nextCounter = 5)
        val plan = merge(snapshot(local), snapshot(imported))
        assertEquals(Long.MAX_VALUE, plan.result.entries.single().nextCounter)
    }

    @Test
    fun generatorIdentityIncludesTypeAlgorithmDigitsAndPeriod() {
        val local = entry(1)
        val variants = listOf(
            entry(2, type = OtpType.HOTP),
            entry(3).copy(algorithm = HashAlgorithm.SHA256),
            entry(4).copy(digits = 8),
            entry(5).copy(periodSeconds = 60),
        )
        val plan = merge(snapshot(local), snapshot(*variants.toTypedArray()))
        assertEquals(listOf(local) + variants, plan.result.entries)
        assertEquals(4, plan.added)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun groupIdCollisionRemapsIncomingReferences() {
        val local = entry(1).copy(groupId = id(10))
        val imported = entry(2, secret = secret(2)).copy(groupId = id(10))
        val current = snapshot(local, groups = listOf(EntryGroup(id(10), "Local")))
        val incoming = snapshot(imported, groups = listOf(EntryGroup(id(10), "Imported")))
        val plan = merge(current, incoming) { id(99) }
        assertEquals(listOf(EntryGroup(id(10), "Local"), EntryGroup(id(99), "Imported")), plan.result.groups)
        assertEquals(listOf(local, imported.copy(groupId = id(99))), plan.result.entries)
        assertEquals(1, plan.remappedIds)
    }

    @Test
    fun identicalGroupsReuseIdWhileNamesAloneDoNotMergeGroups() {
        val current = snapshot(groups = listOf(EntryGroup(id(10), "Work")))
        val incoming = snapshot(groups = listOf(EntryGroup(id(10), "Work"), EntryGroup(id(11), "Work")))
        val plan = merge(current, incoming)
        assertEquals(listOf(EntryGroup(id(10), "Work"), EntryGroup(id(11), "Work")), plan.result.groups)
        assertEquals(0, plan.remappedIds)
    }

    @Test
    fun duplicateEntryPreservesLocalGroupWhenIncomingGroupConflicts() {
        val local = entry(1).copy(groupId = id(10))
        val imported = entry(2).copy(groupId = id(10))
        val current = snapshot(local, groups = listOf(EntryGroup(id(10), "Local")))
        val incoming = snapshot(imported, groups = listOf(EntryGroup(id(10), "Incoming")))
        val plan = merge(current, incoming) { id(99) }
        assertEquals(listOf(local), plan.result.entries)
        assertEquals(id(10), plan.result.entries.single().groupId)
    }

    @Test
    fun generatedIdsSkipAllReservedIncomingAndCurrentIds() {
        val local = entry(1)
        val conflicting = entry(1, secret = secret(2))
        val future = entry(2, secret = secret(3))
        val candidates = listOf(id(1), id(2), id(10), id(99)).iterator()
        val plan = merge(
            snapshot(local, groups = listOf(EntryGroup(id(10), "Work"))),
            snapshot(conflicting, future),
        ) { candidates.next() }
        assertEquals(listOf(id(1), id(99), id(2)), plan.result.entries.map { it.id })
        assertEquals(1, plan.remappedIds)
    }

    @Test
    fun exhaustedIdFactoryRejectsInsteadOfLoopingForever() {
        assertThrows(IllegalArgumentException::class.java) {
            merge(snapshot(entry(1)), snapshot(entry(1, secret = secret(2)))) { id(1) }
        }
    }

    @Test
    fun duplicateIncomingGeneratorsMergeWithoutLosingMaximumCounter() {
        val first = entry(1, type = OtpType.HOTP, nextCounter = 5)
        val second = entry(2, type = OtpType.HOTP, nextCounter = 9)
        val plan = merge(snapshot(), snapshot(first, second))
        assertEquals(listOf(first.copy(nextCounter = 9)), plan.result.entries)
        assertEquals(1, plan.added)
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(id(1)), plan.hotpWarnings)
    }

    @Test
    fun replacePreservesIncomingSnapshotContentsWithoutMutatingCurrent() {
        val local = entry(1, type = OtpType.HOTP, nextCounter = 99)
        val imported = entry(1, type = OtpType.HOTP, nextCounter = 5).copy(groupId = id(10))
        val current = snapshot(local, revision = 7)
        val incoming = snapshot(imported, groups = listOf(EntryGroup(id(10), "Imported")), revision = 2)
        val plan = RestorePlanner.plan(current, incoming, RestoreMode.REPLACE) { error("Новый ID не нужен") }
        assertEquals(listOf(imported), plan.result.entries)
        assertEquals(incoming.groups, plan.result.groups)
        assertEquals(7L, plan.result.revision)
        assertEquals(listOf(local), current.entries)
        assertEquals(99L, current.entries.single().nextCounter)
        assertEquals(1, plan.added)
        assertEquals(0, plan.duplicates)
        assertEquals(0, plan.remappedIds)
        assertEquals(listOf(id(1)), plan.hotpWarnings)
    }

    @Test
    fun emptyMergePreservesExistingDuplicateGeneratorsAndRevision() {
        val first = entry(1)
        val second = entry(2)
        val plan = merge(snapshot(first, second, revision = 7), snapshot())
        assertEquals(listOf(first, second), plan.result.entries)
        assertEquals(7L, plan.result.revision)
        assertEquals(0, plan.added)
        assertEquals(emptyList<UUID>(), plan.hotpWarnings)
    }

    @Test
    fun planWarningsOwnTheirImmutableCollection() {
        val ids = mutableListOf(id(1))
        val plan = RestorePlan(snapshot(), 0, 0, 0, ids)
        ids.clear()
        assertEquals(listOf(id(1)), plan.hotpWarnings)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.hotpWarnings as MutableList<UUID>).clear()
        }
    }

    @Test
    fun mergeAboveEntryLimitRejectsWholeResultWithoutChangingInputs() {
        val current = snapshot(*(1L..10_000L).map { entry(it, secret = numberedSecret(it)) }.toTypedArray())
        val imported = entry(20_000, secret = numberedSecret(20_000))
        val incoming = snapshot(imported)
        assertThrows(IllegalArgumentException::class.java) { merge(current, incoming) }
        assertEquals(10_000, current.entries.size)
        assertEquals(listOf(imported), incoming.entries)
    }

    @Test
    fun mergeAtEntryLimitStillAcceptsDuplicateWithoutAddingRecord() {
        val current = snapshot(*(1L..10_000L).map { entry(it, secret = numberedSecret(it)) }.toTypedArray())
        val duplicate = current.entries.first().copy(id = id(20_000), issuer = "Imported")
        val plan = merge(current, snapshot(duplicate))
        assertEquals(10_000, plan.result.entries.size)
        assertEquals(0, plan.added)
        assertEquals(1, plan.duplicates)
        assertNotEquals("Imported", plan.result.entries.first().issuer)
    }

    private fun merge(current: VaultSnapshot, incoming: VaultSnapshot, newId: () -> UUID = { error("Новый ID не нужен") }) =
        RestorePlanner.plan(current, incoming, RestoreMode.MERGE, newId)

    private fun snapshot(vararg entries: OtpEntry, groups: List<EntryGroup> = emptyList(), revision: Long = 0) =
        VaultSnapshot(revision = revision, entries = entries.toList(), groups = groups)

    private fun entry(id: Long, secret: OtpSecret = secret(1), type: OtpType = OtpType.TOTP, nextCounter: Long = 0) =
        OtpEntry(
            id = UUID(0, id), issuer = "Example", accountName = "synthetic@example.invalid", secret = secret,
            type = type, algorithm = HashAlgorithm.SHA1, digits = 6, periodSeconds = 30,
            nextCounter = nextCounter, groupId = null, favorite = false, sortIndex = 0,
        )

    private fun secret(value: Int) = OtpSecret.fromBytes(ByteArray(20) { value.toByte() })

    private fun numberedSecret(value: Long) = OtpSecret.fromBytes(ByteArray(20) { index ->
        if (index < 8) (value ushr (8 * index)).toByte() else 0
    })

    private fun id(value: Long) = UUID(0, value)
}
