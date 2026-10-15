package dev.openauth.core.backup

import dev.openauth.core.model.*
import dev.openauth.core.restore.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SnapshotLimitsTest {
    private fun group(i: Int) = EntryGroup(UUID(0, i.toLong()), "Group $i")

    @Test fun modelAcceptsGroupLimitAndRejectsNextGroup() {
        val groups = List(VaultSnapshot.MAX_GROUPS) { group(it) }
        val snapshot = VaultSnapshot(1, 0, emptyList(), groups)
        assertEquals(groups, SnapshotJsonCodec.decode(SnapshotJsonCodec.encode(snapshot)).groups)
        assertThrows(IllegalArgumentException::class.java) { VaultSnapshot(1, 0, emptyList(), groups + group(groups.size)) }
    }

    @Test fun decoderStopsAtLimitBeforeReadingMalformedExtraElement() {
        val groups = List(VaultSnapshot.MAX_GROUPS) { "{\"id\":\"${UUID(0, it.toLong())}\",\"name\":\"x\"}" }.joinToString(",")
        val json = "{\"schemaVersion\":1,\"revision\":0,\"entries\":[],\"groups\":[$groups,{\"unexpected\":\"ignored\"}]}"
        assertEquals(BackupFailure.LIMIT_EXCEEDED,
            assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(json.toByteArray()) }.reason)
    }

    @Test fun mergeFailsAtGroupLimitButStillAllowsExistingGroup() {
        val current = VaultSnapshot(1, 0, emptyList(), List(VaultSnapshot.MAX_GROUPS) { group(it) })
        val incoming = VaultSnapshot(1, 0, emptyList(), listOf(group(VaultSnapshot.MAX_GROUPS)))
        assertEquals(RestoreFailure.GROUP_LIMIT_EXCEEDED, assertThrows(RestorePlanningException::class.java) {
            RestorePlanner.plan(current, incoming, RestoreMode.MERGE) { UUID.randomUUID() }
        }.reason)
        val duplicate = VaultSnapshot(1, 0, emptyList(), listOf(group(0)))
        assertEquals(VaultSnapshot.MAX_GROUPS, RestorePlanner.plan(current, duplicate, RestoreMode.MERGE) { UUID.randomUUID() }.result.groups.size)
    }

    @Test fun decoderRejectsTooMuchHistoryBeforeBuildingAllFloors() {
        val floors = List(VaultSnapshot.MAX_HOTP_COUNTER_FLOORS + 1) {
            "{\"fingerprint\":\"${it.toString(16).padStart(64, '0')}\",\"nextCounter\":1}"
        }.joinToString(",")
        val json = "{\"schemaVersion\":2,\"revision\":0,\"entries\":[],\"groups\":[],\"hotpCounterFloors\":[$floors]}"
        assertEquals(BackupFailure.LIMIT_EXCEEDED,
            assertThrows(BackupException::class.java) { SnapshotJsonCodec.decode(json.toByteArray()) }.reason)
    }
}
