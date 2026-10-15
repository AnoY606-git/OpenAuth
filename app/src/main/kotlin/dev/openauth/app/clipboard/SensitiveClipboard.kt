package dev.openauth.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID

/** Проверка владельца никогда не читает сам код; ожидание очистки хранит только token и deadline. */
class SensitiveClipboard(
    context: Context,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val scheduleExpiry: (() -> Unit) -> Unit = { action -> Handler(Looper.getMainLooper()).postDelayed(action, 30_000L); Unit },
) {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)

    fun copyCode(code: String) {
        require(code.length in setOf(6, 8) && code.all { it in '0'..'9' })
        val owner = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("Open Auth", code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString(OWNER_KEY, owner)
        }
        synchronized(cleanupGuard) {
            clipboard.setPrimaryClip(clip)
            pendingCleanup = PendingCleanup(owner, nowMillis() + 30_000L)
        }
        // Callback не захватывает code/ClipData и не отменяется при блокировке vault.
        scheduleExpiry { retryExpiredClip(owner) }
    }

    /** Повторяется при resume/input focus, если Android не разрешил прочитать metadata в фоне. */
    fun retryExpiredClip() = retryExpiredClip(expectedOwner = null)

    private fun retryExpiredClip(expectedOwner: String?) {
        synchronized(cleanupGuard) {
            val pending = pendingCleanup ?: return
            if (expectedOwner != null && pending.owner != expectedOwner) return
            if (nowMillis() < pending.deadlineMillis) return
            try {
                // null означает также отсутствие input focus, поэтому не теряем ожидающую очистку.
                val description = clipboard.primaryClipDescription ?: return
                if (description.extras?.getString(OWNER_KEY) == pending.owner) clipboard.clearPrimaryClip()
                // Доступные metadata с другим владельцем означают более новый clip: его не удаляем.
                pendingCleanup = null
            } catch (_: SecurityException) {
                // Разрешение может измениться между callback и Binder: повторим после возврата.
            }
        }
    }

    private data class PendingCleanup(val owner: String, val deadlineMillis: Long)
    private companion object {
        const val OWNER_KEY = "dev.openauth.clip.owner"
        val cleanupGuard = Any()
        // Переживает смену Activity в пределах процесса; в persistent storage не записывается.
        var pendingCleanup: PendingCleanup? = null
    }
}
