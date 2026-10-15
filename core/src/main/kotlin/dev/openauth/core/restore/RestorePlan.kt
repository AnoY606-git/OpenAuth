package dev.openauth.core.restore

import dev.openauth.core.model.VaultSnapshot
import dev.openauth.core.model.OtpType
import dev.openauth.core.model.HashAlgorithm
import java.util.Collections
import java.util.UUID

enum class RestoreMode { MERGE, REPLACE }

enum class RestoreFailure { ID_ALLOCATION_EXHAUSTED, ENTRY_LIMIT_EXCEEDED }

/** Ошибка расчёта плана: ни один входной снимок при этом не изменяется. */
class RestorePlanningException(val reason: RestoreFailure) : IllegalArgumentException(
    when (reason) {
        RestoreFailure.ID_ALLOCATION_EXHAUSTED -> "Не удалось получить свободный ID для восстановления"
        RestoreFailure.ENTRY_LIMIT_EXCEEDED -> "Восстановление превысит ограничение в 10 000 записей"
    },
)

/**
 * Предпросмотр восстановления. Ревизия результата равна локальной ревизии;
 * сохранение с проверкой актуальности и увеличением ревизии выполняет вызывающий код.
 * hotpWarnings содержит уникальные ID восстановленных HOTP: счётчик сервиса может быть выше.
 * remappedIds учитывает переназначения как записей, так и групп.
 */
/** Только отображаемые метаданные: setup secret и URI намеренно отсутствуют. */
data class RestoreAccountMetadata(val issuer: String, val accountName: String, val type: OtpType, val algorithm: HashAlgorithm, val digits: Int, val periodSeconds: Int, val nextCounter: Long, val groupName: String?, val favorite: Boolean, val sortIndex: Long)
data class RestoreAccountPreview(val incoming: RestoreAccountMetadata, val retained: RestoreAccountMetadata? = null)

class RestorePlan(
    val result: VaultSnapshot,
    val added: Int,
    val duplicates: Int,
    val remappedIds: Int,
    hotpWarnings: List<UUID>,
    accounts: List<RestoreAccountPreview> = emptyList(),
) {
    val accounts: List<RestoreAccountPreview> = Collections.unmodifiableList(ArrayList(accounts))
    val hotpWarnings: List<UUID> = Collections.unmodifiableList(ArrayList(hotpWarnings))
}
