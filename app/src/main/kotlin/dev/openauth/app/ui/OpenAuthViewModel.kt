package dev.openauth.app.ui

import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultState
import dev.openauth.core.model.*
import dev.openauth.core.otp.Base32
import dev.openauth.core.otp.OtpEngine
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

enum class AccountSort { ISSUER, ACCOUNT, ADDED }

data class AccountDraft(
    val id: UUID? = null,
    val issuer: String = "",
    val accountName: String = "",
    val secret: String = "",
    val type: OtpType = OtpType.TOTP,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = 6,
    val period: String = "30",
    val counter: String = "0",
    val groupId: UUID? = null,
)

/** Решения представления не зависят от Android и не сохраняют секреты в SavedState. */
class OpenAuthViewModel(val repository: VaultRepository) {
    val vaultState = repository.state
    private val deletion = MutableStateFlow<UUID?>(null)
    val pendingDelete = deletion.asStateFlow()

    suspend fun save(draft: AccountDraft) {
        val issuer = draft.issuer.trim()
        val account = draft.accountName.trim()
        require(account.isNotEmpty() && account.length <= 256)
        require(issuer.length <= 256)
        require((draft.issuer + draft.accountName).none { it.isISOControl() })
        repository.mutate { snapshot ->
            val old = draft.id?.let { id -> snapshot.entries.firstOrNull { it.id == id } ?: error("Запись отсутствует") }
            val secret = if (draft.secret.isBlank() && old != null) old.secret else Base32.decode(draft.secret)
            val counter = draft.counter.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Счётчик")
            val period = draft.period.toIntOrNull() ?: throw IllegalArgumentException("Период")
            require(draft.groupId == null || snapshot.groups.any { it.id == draft.groupId })
            val entry = OtpEntry(old?.id ?: UUID.randomUUID(), issuer, account, secret,
                draft.type, draft.algorithm, draft.digits, period, counter, draft.groupId, old?.favorite ?: false,
                old?.sortIndex ?: ((snapshot.entries.maxOfOrNull { it.sortIndex } ?: -1L) + 1))
            VaultSnapshot(revision = snapshot.revision, groups = snapshot.groups,
                entries = if (old == null) snapshot.entries + entry else snapshot.entries.map { if (it.id == old.id) entry else it })
        }
    }

    suspend fun addImported(entries: List<OtpEntry>) {
        require(entries.isNotEmpty())
        repository.mutate { current ->
            val first = (current.entries.maxOfOrNull { it.sortIndex } ?: -1L) + 1
            VaultSnapshot(revision = current.revision, groups = current.groups,
                entries = current.entries + entries.mapIndexed { index, entry -> entry.copy(sortIndex = first + index) })
        }
    }

    suspend fun toggleFavorite(id: UUID) = update(id) { it.copy(favorite = !it.favorite) }
    suspend fun assignGroup(id: UUID, groupId: UUID?) = update(id) { it.copy(groupId = groupId) }
    suspend fun createGroup(name: String): UUID {
        val checked = name.trim()
        require(checked.isNotEmpty() && checked.length <= 256 && checked.none { it.isISOControl() })
        val id = UUID.randomUUID()
        repository.mutate { current ->
            require(current.groups.size < VaultSnapshot.MAX_GROUPS) { "Достигнут предел числа групп" }
            require(current.groups.none { it.name.equals(checked, true) })
            VaultSnapshot(revision = current.revision, entries = current.entries, groups = current.groups + EntryGroup(id, checked))
        }
        return id
    }
    fun requestDelete(id: UUID) { deletion.value = id }
    fun cancelDelete() { deletion.value = null }
    suspend fun confirmDelete(id: UUID) {
        if (deletion.value != id) return
        repository.mutate { current ->
            VaultSnapshot(revision = current.revision, groups = current.groups, entries = current.entries.filterNot { it.id == id })
        }
        deletion.value = null
    }
    suspend fun issueHotp(id: UUID): String = repository.issueHotp(id)
    private suspend fun update(id: UUID, transform: (OtpEntry) -> OtpEntry) = repository.mutate { current ->
        VaultSnapshot(revision = current.revision, groups = current.groups,
            entries = current.entries.map { if (it.id == id) transform(it) else it })
    }
}

fun selectAccounts(snapshot: VaultSnapshot, query: String, favoritesOnly: Boolean, groupId: UUID?, sort: AccountSort): List<OtpEntry> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val filtered = snapshot.entries.filter { entry ->
        (!favoritesOnly || entry.favorite) && (groupId == null || entry.groupId == groupId) &&
            (needle.isEmpty() || entry.issuer.lowercase(Locale.ROOT).contains(needle) || entry.accountName.lowercase(Locale.ROOT).contains(needle))
    }
    return when (sort) {
        AccountSort.ISSUER -> filtered.sortedWith(compareBy<OtpEntry> { it.issuer.lowercase(Locale.ROOT) }.thenBy { it.accountName.lowercase(Locale.ROOT) }.thenBy { it.id })
        AccountSort.ACCOUNT -> filtered.sortedWith(compareBy<OtpEntry> { it.accountName.lowercase(Locale.ROOT) }.thenBy { it.issuer.lowercase(Locale.ROOT) }.thenBy { it.id })
        AccountSort.ADDED -> filtered.sortedWith(compareBy<OtpEntry> { it.sortIndex }.thenBy { it.id })
    }
}

/** Один холодный поток: repeatOnLifecycle владеет подпиской, задержка не служит источником времени. */
class OtpClock(private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    fun ticks() = flow { while (true) { emit(epochSeconds()); delay(1000) } }
}

/** Невидимые записи и HOTP не передаются генератору даже при частых перерисовках списка. */
class VisibleCodeGenerator(private val totp: (OtpEntry, Long) -> String = OtpEngine::totp) {
    fun generate(entries: List<OtpEntry>, visibleIds: Set<UUID>, epochSeconds: Long): Map<UUID, String> =
        entries.asSequence().filter { it.id in visibleIds && it.type == OtpType.TOTP }
            .associate { it.id to totp(it, epochSeconds) }
}
