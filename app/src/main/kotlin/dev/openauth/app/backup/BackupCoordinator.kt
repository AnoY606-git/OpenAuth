package dev.openauth.app.backup

import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultState
import dev.openauth.core.backup.BackupCodec
import dev.openauth.core.backup.BackupCredential
import dev.openauth.core.backup.BackupGeneration
import dev.openauth.core.model.VaultSnapshot
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.restore.RestorePlan
import dev.openauth.core.restore.RestorePlanner
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Владелец plaintext только на время операции. Пароли потребляются и закрываются даже при отмене. */
class BackupCoordinator(
    private val repository: VaultRepository,
    private val queue: EncryptedSnapshotQueue,
    private val codec: BackupCodec = BackupCodec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val automaticSnapshotsEnabled: Boolean = true,
) {
    val files = BackupFileSession()
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<BackupState>(BackupState.NeverCreated)
    val state: StateFlow<BackupState> = mutableState.asStateFlow()
    private data class Preview(val plan: RestorePlan, val base: VaultSnapshot? = null, val recovery: VaultRepository.RecoveryTicket? = null)
    private var preview: Preview? = null

    suspend fun configure(credential: BackupCredential) = replaceGeneration(credential)
    suspend fun rotate(credential: BackupCredential) = replaceGeneration(credential)

    private suspend fun replaceGeneration(credential: BackupCredential) {
        try { withContext(dispatcher) { mutex.withLock {
            val base = current()
            val generation = kdfMutex.withLock { codec.createGeneration(credential) }
            generation.use {
                currentCoroutineContext().ensureActive(); requireCurrent(base)
                val local = it.exportLocalState()
                try { repository.saveBackupGenerationState(local, expectedSnapshot = base) } finally { local.fill(0) }
            }
            // Если очередь недоступна, новое поколение уже сохранено. Следующий unlock повторит snapshot.
            recoverLocked()
        } } } finally { credential.close() }
    }

    suspend fun isConfigured(): Boolean = withContext(dispatcher) {
        repository.readBackupGenerationState()?.let { it.fill(0); true } ?: false
    }

    suspend fun exportSnapshot(): ByteArray = withContext(dispatcher) { mutex.withLock {
        val base = current()
        val local = repository.readBackupGenerationState() ?: throw BackupFlowException(BackupFlowFailure.NOT_CONFIGURED)
        try {
            val encrypted = BackupGeneration.importLocalState(local).use { codec.seal(base, it, UUID.randomUUID(), now()) }
            currentCoroutineContext().ensureActive(); requireCurrent(base)
            encrypted
        } finally { local.fill(0) }
    } }

    suspend fun snapshotNow(): PendingSnapshot = withContext(dispatcher) { mutex.withLock { snapshotLocked() } }

    /** Вызывать после unlock, включения Drive, а также после изменения vault; idempotent по поколению и ревизии. */
    suspend fun recoverPending() = withContext(dispatcher) { mutex.withLock { recoverLocked() } }

    /** Scope принадлежит Application. Заблокированная сессия не запускает KDF или снимки. */
    fun startObserving(scope: CoroutineScope): Job = scope.launch {
        launch { queue.state.collect { mutableState.value = it } }
        repository.state.collect { value ->
            if (value is VaultState.Unlocked) {
                try { recoverPending() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.value = BackupState.Failure(BackupFlowFailure.STORAGE) }
            } else mutex.withLock { preview = null }
        }
    }

    private suspend fun recoverLocked() {
        // Keep old cloud ciphertext/configuration dormant; explicit file export is independent.
        if (!automaticSnapshotsEnabled) return
        if (queue.activeConfigurationId() == null) return
        val base = current()
        val local = repository.readBackupGenerationState() ?: return
        val generationId = try { fingerprint(local) } finally { local.fill(0) }
        requireCurrent(base)
        val checkpoint = queue.checkpoint()
        if (checkpoint != null && checkpoint.snapshot.revision == base.revision && checkpoint.snapshot.generationId == generationId &&
            (checkpoint.uploaded || (checkpoint.snapshot.file.isFile && checkpoint.snapshot.file.length() in 176L..16L * 1024 * 1024))) {
            mutableState.value = if (checkpoint.uploaded) BackupState.Uploaded(checkpoint.snapshot) else BackupState.Pending(checkpoint.snapshot)
            return
        }
        snapshotLocked()
    }

    private suspend fun snapshotLocked(): PendingSnapshot {
        val configuration = queue.activeConfigurationId() ?: throw BackupFlowException(BackupFlowFailure.NOT_CONFIGURED)
        val base = current()
        val local = repository.readBackupGenerationState() ?: throw BackupFlowException(BackupFlowFailure.NOT_CONFIGURED)
        try {
            val id = UUID.randomUUID(); val time = now(); val generationId = fingerprint(local)
            val encrypted = BackupGeneration.importLocalState(local).use { codec.seal(base, it, id, time) }
            currentCoroutineContext().ensureActive(); requireCurrent(base)
            return try {
                queue.enqueue(encrypted, base.revision, generationId, time, id, configuration).also {
                    mutableState.value = BackupState.Pending(it)
                }
            } catch (failure: Exception) {
                mutableState.value = BackupState.Failure(BackupFlowFailure.STORAGE)
                throw failure
            }
        } finally { local.fill(0) }
    }

    suspend fun previewRestore(container: ByteArray, credential: BackupCredential, mode: RestoreMode): RestorePlan {
        try { return withContext(dispatcher) { mutex.withLock {
            preview = null
            val base = current()
            val incoming = kdfMutex.withLock { codec.open(container, credential) }
            currentCoroutineContext().ensureActive(); requireCurrent(base)
            val plan = RestorePlanner.plan(base, incoming, mode, UUID::randomUUID)
            preview = Preview(plan, base = base)
            plan
        } } } finally { credential.close() }
    }

    suspend fun previewRecoveryRestore(container: ByteArray, credential: BackupCredential): RestorePlan {
        try { return withContext(dispatcher) { mutex.withLock {
            preview = null
            val ticket = repository.beginRecovery()
            val incoming = kdfMutex.withLock { codec.open(container, credential) }
            currentCoroutineContext().ensureActive()
            repository.validateRecovery(ticket)
            val empty = VaultSnapshot(revision = 0, entries = emptyList(), groups = emptyList())
            val plan = RestorePlanner.plan(empty, incoming, RestoreMode.REPLACE, UUID::randomUUID)
            preview = Preview(plan, recovery = ticket)
            plan
        } } } finally { credential.close() }
    }

    suspend fun cancelRestore() = mutex.withLock { preview = null }

    suspend fun commitRestore(plan: RestorePlan, authenticateRecovery: suspend () -> Unit = { throw dev.openauth.app.vault.VaultException(dev.openauth.app.vault.VaultFailure.AUTHENTICATION_REQUIRED) }) = withContext(dispatcher) { mutex.withLock {
        val staged = preview ?: throw BackupFlowException(BackupFlowFailure.STALE_PREVIEW)
        if (staged.plan !== plan) throw BackupFlowException(BackupFlowFailure.STALE_PREVIEW)
        preview = null
        if (staged.recovery != null) repository.recover(staged.recovery, plan.result, authenticateRecovery)
        else repository.mutate { base ->
            if (base !== staged.base) throw BackupFlowException(BackupFlowFailure.STALE_PREVIEW)
            plan.result
        }
        // Успешное восстановление не откатывается из-за отдельной ошибки резервного копирования.
        try { recoverLocked() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = BackupState.Failure(BackupFlowFailure.STORAGE) }
    } }

    private fun current(): VaultSnapshot = (repository.state.value as? VaultState.Unlocked)?.snapshot
        ?: throw BackupFlowException(BackupFlowFailure.LOCKED_OR_CHANGED)
    private fun requireCurrent(base: VaultSnapshot) {
        if ((repository.state.value as? VaultState.Unlocked)?.snapshot !== base) throw BackupFlowException(BackupFlowFailure.LOCKED_OR_CHANGED)
    }
    private fun fingerprint(local: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(local.copyOfRange(8, 128)).joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { private val kdfMutex = Mutex() }
}
