package dev.openauth.core.model

import java.util.Collections
import java.util.UUID

data class EntryGroup(val id: UUID, val name: String)

/** Снимок владеет неизменяемыми списками, чтобы внешние изменения не нарушали связи. */
class VaultSnapshot(
    val schemaVersion: Int = 1,
    val revision: Long,
    entries: List<OtpEntry>,
    groups: List<EntryGroup>,
) {
    val entries: List<OtpEntry> = Collections.unmodifiableList(ArrayList(entries))
    val groups: List<EntryGroup> = Collections.unmodifiableList(ArrayList(groups))

    init {
        require(schemaVersion == 1) { "Неподдерживаемая версия схемы хранилища" }
        require(revision >= 0) { "Ревизия хранилища не может быть отрицательной" }
        require(this.entries.size <= 10_000) { "Хранилище содержит больше 10 000 записей" }
        require(this.entries.map { it.id }.toSet().size == this.entries.size) { "ID записей должны быть уникальны" }
        val groupIds = this.groups.map { it.id }.toSet()
        require(groupIds.size == this.groups.size) { "ID групп должны быть уникальны" }
        require(this.entries.all { it.groupId == null || it.groupId in groupIds }) { "Запись ссылается на неизвестную группу" }
    }
}
