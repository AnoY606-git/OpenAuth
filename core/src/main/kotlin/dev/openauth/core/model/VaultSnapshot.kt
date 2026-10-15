package dev.openauth.core.model

import java.util.Collections
import java.util.UUID

data class EntryGroup(val id: UUID, val name: String) {
    init {
        require(name.isNotBlank() && name.length <= 256 && name.none { it.isISOControl() }) {
            "Название группы должно содержать от 1 до 256 символов без управляющих символов"
        }
    }
}

/** Снимок владеет неизменяемыми списками, чтобы внешние изменения не нарушали связи. */
class VaultSnapshot(
    val schemaVersion: Int = 1,
    val revision: Long,
    entries: List<OtpEntry>,
    groups: List<EntryGroup>,
    hotpCounterFloors: List<HotpCounterFloor> = emptyList(),
) {
    val entries: List<OtpEntry> = Collections.unmodifiableList(ArrayList(entries))
    val groups: List<EntryGroup> = Collections.unmodifiableList(ArrayList(groups))
    val hotpCounterFloors: List<HotpCounterFloor> = Collections.unmodifiableList(ArrayList(hotpCounterFloors))

    init {
        require(schemaVersion in 1..2) { "Неподдерживаемая версия схемы хранилища" }
        require(revision >= 0) { "Ревизия хранилища не может быть отрицательной" }
        require(this.entries.size <= 10_000) { "Хранилище содержит больше 10 000 записей" }
        require(this.groups.size <= MAX_GROUPS) { "Хранилище содержит слишком много групп" }
        require(this.hotpCounterFloors.size <= MAX_HOTP_COUNTER_FLOORS) { "История HOTP заполнена" }
        require(schemaVersion == 2 || this.hotpCounterFloors.isEmpty()) { "История HOTP требует схему 2" }
        require(this.hotpCounterFloors.map { it.fingerprint }.toSet().size == this.hotpCounterFloors.size) { "История HOTP содержит дубликаты" }
        require(this.entries.map { it.id }.toSet().size == this.entries.size) { "ID записей должны быть уникальны" }
        val groupIds = this.groups.map { it.id }.toSet()
        require(groupIds.size == this.groups.size) { "ID групп должны быть уникальны" }
        require(this.entries.all { it.groupId == null || it.groupId in groupIds }) { "Запись ссылается на неизвестную группу" }
    }

    companion object {
        const val MAX_GROUPS = 10_000
        // Never silently evict history: a full history must fail before a counter can be reused.
        const val MAX_HOTP_COUNTER_FLOORS = 20_000
    }
}
