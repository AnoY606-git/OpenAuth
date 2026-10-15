package dev.openauth.app.vault

import dev.openauth.core.backup.SnapshotJsonCodec
import dev.openauth.core.model.OtpEntry
import dev.openauth.core.model.OtpGeneratorIdentity
import dev.openauth.core.model.OtpType
import dev.openauth.core.model.VaultSnapshot
import dev.openauth.core.otp.OtpEngine
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface VaultState {
    data object Uninitialized : VaultState
    data object Locked : VaultState
    data class Unlocked(val snapshot: VaultSnapshot) : VaultState
    data class Unavailable(val reason: VaultFailure) : VaultState
}

enum class VaultFailure {
    LOCKED, ALREADY_INITIALIZED, DEVICE_NOT_SECURE, AUTHENTICATION_REQUIRED,
    AUTHENTICATION_CANCELLED, KEY_UNAVAILABLE, KEY_INVALIDATED, CORRUPT_STORAGE, STORAGE_UNAVAILABLE,
}

class VaultException(val reason: VaultFailure) : Exception(reason.name)

interface VaultStorage {
    fun exists(): Boolean = read() != null
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
    fun replacePreservingOriginal(expected: ByteArray, replacement: ByteArray, commit: (() -> Unit) -> Unit = { it() }) { throw IOException("Восстановление не поддерживается") }
}

interface VaultKeyProtector {
    fun newRecoveryProtector(): VaultKeyProtector = throw VaultException(VaultFailure.KEY_UNAVAILABLE)
    fun discardRecoveryKey() {}
    fun prepare(create: Boolean)
    fun prepareForUnlock() = prepare(false)
    fun wrap(key: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** Все записи сериализованы; lock синхронно закрывает доступ независимо от дискового I/O. */
class VaultRepository(
    private val storage: VaultStorage,
    private val keys: VaultKeyProtector,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val guard = Any()
    private var epoch = 0L
    private var session: VaultSession? = null
    private var backupState: ByteArray? = null
    private val mutableState = MutableStateFlow<VaultState>(try {
        if (storage.exists()) VaultState.Locked else VaultState.Uninitialized
    } catch (_: IOException) { VaultState.Unavailable(VaultFailure.STORAGE_UNAVAILABLE) })
    val state: StateFlow<VaultState> = mutableState.asStateFlow()

    /** Непрозрачный снимок основания восстановления; никогда не содержит открытые секреты. */
    class RecoveryTicket internal constructor(internal val owner: VaultRepository, internal val epoch: Long, internal val ciphertext: ByteArray)

    suspend fun beginRecovery(): RecoveryTicket = withContext(dispatcher) { mutex.withLock {
        val token = synchronized(guard) {
            if (session != null) throw VaultException(VaultFailure.LOCKED)
            epoch
        }
        val ciphertext = storage.read() ?: throw VaultException(VaultFailure.CORRUPT_STORAGE)
        requireEpoch(token)
        RecoveryTicket(this@VaultRepository, token, ciphertext)
    } }

    suspend fun validateRecovery(ticket: RecoveryTicket) = withContext(dispatcher) { mutex.withLock {
        requireRecoverySession(ticket)
        if (storage.read()?.contentEquals(ticket.ciphertext) != true) throw VaultException(VaultFailure.LOCKED)
        requireRecoverySession(ticket)
    } }

    private fun requireRecoverySession(ticket: RecoveryTicket) = synchronized(guard) {
        requireEpoch(ticket.epoch)
        if (ticket.owner !== this || session != null) throw VaultException(VaultFailure.LOCKED)
    }

    /** Новый KEK создаётся до настоящего системного prompt; wrap всё равно проверяется Keystore. */
    suspend fun recover(ticket: RecoveryTicket, snapshot: VaultSnapshot, authenticate: suspend () -> Unit) = withContext(dispatcher) { mutex.withLock {
        requireRecoverySession(ticket)
        if (storage.read()?.contentEquals(ticket.ciphertext) != true) throw VaultException(VaultFailure.LOCKED)
        // Полная сериализация проверяет вход до создания ключа или изменения файлов.
        SnapshotJsonCodec.encode(snapshot).fill(0)
        val replacementKeys = keys.newRecoveryProtector()
        var installed = false
        var opened: VaultSession? = null
        val key = VaultCipher.newKey()
        try {
            replacementKeys.prepare(true)
            authenticate()
            currentCoroutineContext().ensureActive(); requireRecoverySession(ticket)
            opened = VaultSession(key, replacementKeys.wrap(key))
            val bytes = encode(opened, snapshot, null)
            currentCoroutineContext().ensureActive()
            val operation = currentCoroutineContext()
            storage.replacePreservingOriginal(ticket.ciphertext, bytes) { install ->
                synchronized(guard) {
                    operation.ensureActive()
                    requireRecoverySession(ticket)
                    install()
                    installed = true
                    publish(ticket.epoch, opened!!, snapshot, null)
                    opened = null
                    // Все другие preview, включая восстановление того же ciphertext, устаревают.
                    epoch++
                }
            }

        } finally {
            key.fill(0); opened?.close()
            if (!installed) replacementKeys.discardRecoveryKey()
        }
    } }

    suspend fun initialize() {
        val token = synchronized(guard) { epoch }
        withContext(dispatcher) { mutex.withLock {
            requireEpoch(token)
            if (storage.read() != null) throw VaultException(VaultFailure.ALREADY_INITIALIZED)
            keys.prepare(true)
            val key = VaultCipher.newKey()
            var opened: VaultSession? = null
            try {
                opened = VaultSession(key, keys.wrap(key))
                val snapshot = VaultSnapshot(revision = 0, entries = emptyList(), groups = emptyList())
                val bytes = encode(opened, snapshot, null)
                currentCoroutineContext().ensureActive()
                requireEpoch(token)
                storage.writeAtomically(bytes)
                publish(token, opened, snapshot, null)
                opened = null
            } finally { key.fill(0); opened?.close() }
        } }
    }

    suspend fun unlock() {
        val token = synchronized(guard) { epoch }
        withContext(dispatcher) { mutex.withLock {
            requireEpoch(token)
            var opened: VaultSession? = null
            try {
                val bytes = storage.read() ?: throw VaultException(VaultFailure.CORRUPT_STORAGE)
                val envelope = parseEnvelope(bytes)
                keys.prepareForUnlock()
                val key = keys.unwrap(envelope.wrapped)
                try {
                    if (key.size != 32) throw VaultException(VaultFailure.CORRUPT_STORAGE)
                    opened = VaultSession(key, envelope.wrapped)
                    val plaintext = VaultCipher.decrypt(key, envelope.ciphertext, envelope.header)
                    try {
                        val (snapshot, privateState) = decodeContainer(plaintext)
                        try {
                            currentCoroutineContext().ensureActive()
                            publish(token, opened, snapshot, privateState)
                            opened = null
                        } finally { privateState?.fill(0) }
                    } finally { plaintext.fill(0) }
                } finally { key.fill(0) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: VaultException) {
                reportUnlockFailure(token, failure.reason)
                throw failure
            } catch (_: IOException) {
                reportUnlockFailure(token, VaultFailure.STORAGE_UNAVAILABLE)
                throw VaultException(VaultFailure.STORAGE_UNAVAILABLE)
            } catch (_: GeneralSecurityException) {
                reportUnlockFailure(token, VaultFailure.CORRUPT_STORAGE)
                throw VaultException(VaultFailure.CORRUPT_STORAGE)
            } catch (_: IllegalArgumentException) {
                reportUnlockFailure(token, VaultFailure.CORRUPT_STORAGE)
                throw VaultException(VaultFailure.CORRUPT_STORAGE)
            } finally { opened?.close() }
        } }
    }

    fun lock() = synchronized(guard) {
        epoch++
        session?.close()
        session = null
        backupState?.fill(0)
        backupState = null
        if (mutableState.value is VaultState.Unlocked) mutableState.value = VaultState.Locked
    }

    /** Ревизией владеет repository: transform меняет содержимое, успешный commit увеличивает revision на один. */
    suspend fun mutate(transform: (VaultSnapshot) -> VaultSnapshot) {
        val token = currentToken()
        withContext(dispatcher) { mutex.withLock {
            val before = currentSnapshot(token)
            val transformed = transform(before)
            val entries = preserveHotpCounters(before.entries, transformed.entries)
            commit(token, VaultSnapshot(transformed.schemaVersion, Math.addExact(before.revision, 1), entries, transformed.groups))
        } }
    }

    suspend fun issueHotp(id: UUID): String {
        val token = currentToken()
        return withContext(dispatcher) { mutex.withLock {
            val before = currentSnapshot(token)
            val entry = before.entries.single { it.id == id }
            require(entry.type == OtpType.HOTP)
            val counter = before.entries.filter { sameGenerator(it, entry) }.maxOf { it.nextCounter }
            val nextCounter = Math.addExact(counter, 1)
            val entries = before.entries.map { if (sameGenerator(it, entry)) it.copy(nextCounter = nextCounter) else it }
            commit(token, VaultSnapshot(before.schemaVersion, Math.addExact(before.revision, 1), entries, before.groups))
            // Код появляется только после устойчивого резервирования; lock запрещает позднюю выдачу.
            val code = OtpEngine.hotp(entry.secret, counter, entry.digits)
            requireEpoch(token)
            code
        } }
    }

    suspend fun readBackupGenerationState(): ByteArray? {
        val token = currentToken()
        return mutex.withLock { synchronized(guard) {
            requireSession(token)
            backupState?.copyOf()
        } }
    }

    suspend fun saveBackupGenerationState(bytes: ByteArray?, expectedSnapshot: VaultSnapshot? = null) {
        require(bytes == null || bytes.size == 160) { "Неверный размер локального поколения копий" }
        val copy = bytes?.copyOf()
        try {
            val token = currentToken()
            withContext(dispatcher) { mutex.withLock {
                val current = currentSnapshot(token)
                if (expectedSnapshot != null && current !== expectedSnapshot) throw VaultException(VaultFailure.LOCKED)
                commit(token, current, copy, replaceBackup = true)
            } }
        } finally { copy?.fill(0) }
    }

    private suspend fun commit(token: Long, snapshot: VaultSnapshot, privateState: ByteArray? = null, replaceBackup: Boolean = false) {
        val (opened, retained) = synchronized(guard) {
            requireSession(token) to (if (replaceBackup) privateState?.copyOf() else backupState?.copyOf())
        }
        try {
            val bytes = encode(opened, snapshot, retained)
            currentCoroutineContext().ensureActive()
            requireEpoch(token)
            storage.writeAtomically(bytes)
            synchronized(guard) {
                requireSession(token)
                backupState?.fill(0)
                backupState = retained?.copyOf()
                mutableState.value = VaultState.Unlocked(snapshot)
            }
        } finally { retained?.fill(0) }
    }

    private fun publish(token: Long, opened: VaultSession, snapshot: VaultSnapshot, privateState: ByteArray?) = synchronized(guard) {
        requireEpoch(token)
        session?.close()
        session = opened
        backupState?.fill(0)
        backupState = privateState?.copyOf()
        mutableState.value = VaultState.Unlocked(snapshot)
    }

    private fun currentToken(): Long = synchronized(guard) {
        if (session == null) throw VaultException(VaultFailure.LOCKED)
        epoch
    }
    private fun currentSnapshot(token: Long): VaultSnapshot = synchronized(guard) {
        requireSession(token)
        (mutableState.value as VaultState.Unlocked).snapshot
    }
    private fun requireSession(token: Long): VaultSession {
        requireEpoch(token)
        return session ?: throw VaultException(VaultFailure.LOCKED)
    }
    private fun requireEpoch(token: Long) = synchronized(guard) {
        if (epoch != token) throw VaultException(VaultFailure.LOCKED)
    }
    private fun reportUnlockFailure(token: Long, failure: VaultFailure) = synchronized(guard) {
        if (epoch == token && failure != VaultFailure.LOCKED) {
            session?.close(); session = null
            backupState?.fill(0); backupState = null
            mutableState.value = if (failure == VaultFailure.AUTHENTICATION_REQUIRED) VaultState.Locked else VaultState.Unavailable(failure)
        }
    }

    private fun encode(opened: VaultSession, snapshot: VaultSnapshot, privateState: ByteArray?): ByteArray {
        val json = SnapshotJsonCodec.encode(snapshot)
        val plaintext = try {
            ByteBuffer.allocate(12 + json.size + (privateState?.size ?: 0))
                .putInt(CONTAINER_VERSION).putInt(json.size).put(json).putInt(privateState?.size ?: 0)
                .apply { privateState?.let { put(it) } }.array()
        } finally { json.fill(0) }
        val header = ByteBuffer.allocate(8 + opened.wrappedKey.size).putInt(MAGIC)
            .putInt(opened.wrappedKey.size).put(opened.wrappedKey).array()
        var key: ByteArray? = null
        return try {
            key = opened.copyKey()
            header + VaultCipher.encrypt(key, plaintext, header)
        } finally { key?.fill(0); plaintext.fill(0) }
    }

    private data class Envelope(val header: ByteArray, val wrapped: ByteArray, val ciphertext: ByteArray)
    private fun parseEnvelope(bytes: ByteArray): Envelope {
        if (bytes.size !in 37..MAX_FILE_BYTES) corrupt()
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) corrupt()
        val length = buffer.int
        if (length !in 1..256 || length > buffer.remaining() - 28) corrupt()
        val wrapped = ByteArray(length).also { buffer.get(it) }
        return Envelope(bytes.copyOfRange(0, buffer.position()), wrapped, bytes.copyOfRange(buffer.position(), bytes.size))
    }
    private fun decodeContainer(bytes: ByteArray): Pair<VaultSnapshot, ByteArray?> {
        if (bytes.size < 12) corrupt()
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != CONTAINER_VERSION) corrupt()
        val length = buffer.int
        if (length !in 1..SnapshotJsonCodec.MAX_BYTES || length > buffer.remaining() - 4) corrupt()
        val json = ByteArray(length).also { buffer.get(it) }
        val snapshot = try { SnapshotJsonCodec.decode(json) }
        catch (_: Exception) { corrupt() }
        finally { json.fill(0) }
        val privateLength = buffer.int
        if (privateLength != 0 && privateLength != 160 || privateLength != buffer.remaining()) corrupt()
        return snapshot to if (privateLength == 0) null else ByteArray(privateLength).also { buffer.get(it) }
    }

    private fun preserveHotpCounters(before: List<OtpEntry>, after: List<OtpEntry>): List<OtpEntry> {
        val maxima = HashMap<OtpGeneratorIdentity, Long>()
        (before.asSequence() + after.asSequence()).filter { it.type == OtpType.HOTP }.forEach {
            val key = OtpGeneratorIdentity.of(it)
            maxima[key] = maxOf(maxima[key] ?: 0, it.nextCounter)
        }
        return after.map { if (it.type == OtpType.HOTP) it.copy(nextCounter = maxima.getValue(OtpGeneratorIdentity.of(it))) else it }
    }
    private fun sameGenerator(a: OtpEntry, b: OtpEntry) = OtpGeneratorIdentity.of(a) == OtpGeneratorIdentity.of(b)
    private fun corrupt(): Nothing = throw VaultException(VaultFailure.CORRUPT_STORAGE)

    companion object {
        internal const val MAX_FILE_BYTES = SnapshotJsonCodec.MAX_BYTES + 1024
        private const val MAGIC = 0x4f415631 // OAV1
        private const val CONTAINER_VERSION = 1
    }
}
