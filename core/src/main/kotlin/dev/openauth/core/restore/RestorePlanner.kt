package dev.openauth.core.restore

import dev.openauth.core.model.HashAlgorithm
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpSecret
import dev.openauth.core.model.OtpType
import dev.openauth.core.model.VaultSnapshot
import java.util.UUID

/** Рассчитывает восстановление без доступа к хранилищу, времени и случайности. */
object RestorePlanner {
    fun plan(
        current: VaultSnapshot,
        incoming: VaultSnapshot,
        mode: RestoreMode,
        newId: () -> UUID,
    ): RestorePlan {
        // Максимум учитывает все псевдонимы генератора, независимо от режима и порядка.
        val maximumHotpCounters = HashMap<GeneratorIdentity, Long>()
        for (snapshot in listOf(current, incoming)) {
            for (entry in snapshot.entries) {
                if (entry.type == OtpType.HOTP) {
                    val identity = GeneratorIdentity.of(entry)
                    maximumHotpCounters[identity] = maxOf(maximumHotpCounters[identity] ?: 0, entry.nextCounter)
                }
            }
        }
        fun withMaximumCounter(entry: OtpEntry): OtpEntry {
            if (entry.type != OtpType.HOTP) return entry
            val maximum = maximumHotpCounters.getValue(GeneratorIdentity.of(entry))
            return if (entry.nextCounter == maximum) entry else entry.copy(nextCounter = maximum)
        }

        if (mode == RestoreMode.REPLACE) {
            return RestorePlan(
                result = VaultSnapshot(
                    schemaVersion = incoming.schemaVersion,
                    revision = current.revision,
                    entries = incoming.entries.map(::withMaximumCounter),
                    groups = incoming.groups,
                ),
                added = incoming.entries.size,
                duplicates = 0,
                remappedIds = 0,
                hotpWarnings = incoming.entries.filter { it.type == OtpType.HOTP }.map { it.id },
            )
        }

        // Резервируем также будущие входящие ID, чтобы remap не создавал новые коллизии.
        val reservedIds = HashSet<UUID>()
        for (snapshot in listOf(current, incoming)) {
            snapshot.entries.forEach { reservedIds.add(it.id) }
            snapshot.groups.forEach { reservedIds.add(it.id) }
        }
        fun allocateId(): UUID {
            repeat(128) {
                val candidate = newId()
                if (reservedIds.add(candidate)) return candidate
            }
            throw RestorePlanningException(RestoreFailure.ID_ALLOCATION_EXHAUSTED)
        }

        var remappedIds = 0
        val groups = current.groups.toMutableList()
        val groupsById = current.groups.associateBy { it.id }.toMutableMap()
        val importedGroupIds = HashMap<UUID, UUID>()
        for (group in incoming.groups) {
            val local = groupsById[group.id]
            val restored = when {
                local == null -> group
                local == group -> {
                    importedGroupIds[group.id] = group.id
                    continue
                }
                else -> group.copy(id = allocateId()).also { remappedIds++ }
            }
            groups.add(restored)
            groupsById[restored.id] = restored
            importedGroupIds[group.id] = restored.id
        }

        val entries = current.entries.map(::withMaximumCounter).toMutableList()
        val entryIds = current.entries.mapTo(HashSet()) { it.id }
        val identityIndices = HashMap<GeneratorIdentity, Int>()
        current.entries.forEachIndexed { index, entry ->
            identityIndices.putIfAbsent(GeneratorIdentity.of(entry), index)
        }
        var added = 0
        var duplicates = 0
        val hotpWarnings = LinkedHashSet<UUID>()
        for (imported in incoming.entries) {
            val identity = GeneratorIdentity.of(imported)
            val duplicateIndex = identityIndices[identity]
            val restoredId: UUID
            if (duplicateIndex != null) {
                duplicates++
                val local = entries[duplicateIndex]
                restoredId = local.id
            } else {
                if (entries.size >= 10_000) {
                    throw RestorePlanningException(RestoreFailure.ENTRY_LIMIT_EXCEEDED)
                }
                restoredId = if (imported.id in entryIds) {
                    allocateId().also { remappedIds++ }
                } else {
                    imported.id
                }
                val restored = withMaximumCounter(imported).copy(
                    id = restoredId,
                    groupId = imported.groupId?.let { importedGroupIds.getValue(it) },
                )
                identityIndices[identity] = entries.size
                entryIds.add(restoredId)
                entries.add(restored)
                added++
            }
            if (imported.type == OtpType.HOTP) hotpWarnings.add(restoredId)
        }

        return RestorePlan(
            result = VaultSnapshot(
                schemaVersion = current.schemaVersion,
                revision = current.revision,
                entries = entries,
                groups = groups,
            ),
            added = added,
            duplicates = duplicates,
            remappedIds = remappedIds,
            hotpWarnings = hotpWarnings.toList(),
        )
    }

    private data class GeneratorIdentity(
        val type: OtpType,
        val secret: OtpSecret,
        val algorithm: HashAlgorithm,
        val digits: Int,
        val periodSeconds: Int,
    ) {
        companion object {
            fun of(entry: OtpEntry) = GeneratorIdentity(
                entry.type, entry.secret, entry.algorithm, entry.digits, entry.periodSeconds,
            )
        }
    }
}
