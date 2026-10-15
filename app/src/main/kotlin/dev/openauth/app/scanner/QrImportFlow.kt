package dev.openauth.app.scanner

import dev.openauth.core.importing.*
import dev.openauth.core.model.OtpEntry
import java.util.UUID

/** Не пишет хранилище: даже полный импорт сначала требует подтверждения в UI. */
class QrImportFlow {
    private var pending: String? = null
    private val parts = mutableListOf<MigrationPart>()
    var preview: List<OtpEntry> = emptyList()
        private set
    val waitingForParts get() = parts.isNotEmpty() && preview.isEmpty()

    fun accept(raw: String) {
        preview = emptyList()
        if (raw.startsWith("otpauth-migration:", true)) {
            val part = GoogleMigrationDecoder.parse(raw)
            // assemble также проверяет совместимость и повторы до добавления очередной части.
            val candidate = parts + part
            try {
                preview = GoogleMigrationDecoder.assemble(candidate, UUID::randomUUID)
                parts.clear()
            } catch (failure: ImportException) {
                if (failure.reason != ImportFailure.INCOMPLETE_BATCH) throw failure
                parts += part
            }
        } else {
            preview = listOf(OtpUriParser.parse(raw, UUID.randomUUID()))
            parts.clear()
        }
    }
    fun deferUntilUnlocked(raw: String) {
        if (raw.length > 8192 || raw.toByteArray(Charsets.UTF_8).size > 8192) throw ImportException(ImportFailure.URI_TOO_LONG)
        pending = raw
    }
    fun resumeAfterUnlock() { val raw = pending ?: return; pending = null; accept(raw) }
    fun takeConfirmed(): List<OtpEntry> { val result = preview; clear(); return result }
    fun clear() { pending = null; preview = emptyList(); parts.clear() }
}
