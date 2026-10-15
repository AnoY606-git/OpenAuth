package dev.openauth.app.drive

import dev.openauth.app.backup.*
import java.io.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Имя аккаунта сохраняется только внутри ciphertext. Строковое представление его не раскрывает. */
data class DriveDestination(val accountName: String, val configurationId: UUID, val sourceId: UUID) {
    init { require(accountName.isNotBlank() && accountName.length <= 320 && accountName.none { it.isISOControl() }) }
    override fun toString() = "DriveDestination(configurationId=$configurationId, sourceId=$sourceId)"
}

enum class DriveConnectionFailure { STORAGE, UNAVAILABLE, AUTHORIZATION, DESTINATION_CHANGED }
class DriveConnectionException(val failure: DriveConnectionFailure) : Exception(failure.name)
enum class DriveSyncPhase { DISCONNECTED, WAITING_FOR_UNLOCK, NEEDS_BACKUP_PASSWORD, PENDING, UPLOADING, UPLOADED, NEEDS_CONSENT, UNAVAILABLE, RETRYING, FAILED }
data class DriveSyncState(val configurationId: UUID? = null, val phase: DriveSyncPhase = DriveSyncPhase.DISCONNECTED)
class DriveSyncStatus {
    private val mutableState = MutableStateFlow(DriveSyncState())
    val state = mutableState.asStateFlow()
    internal fun publish(configuration: UUID?, phase: DriveSyncPhase) { mutableState.value = DriveSyncState(configuration, phase) }
}

/** Тот же экземпляр служит короткой блокировкой публикации/configuration; сетевых вызовов под ней нет. */
class DriveDestinationStore(private val bytes: AtomicBytes, private val protector: MetadataProtector) {
    internal val operations = Mutex()
    internal var deletionLease: UUID? = null
    @Synchronized fun read(): DriveDestination? = safe {
        val encrypted = bytes.read() ?: return@safe null
        val plain = protector.open(encrypted)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                if (plain.size > 2048 || input.readUTF() != DOMAIN) throw IOException()
                val result = if (input.readBoolean()) DriveDestination(input.readUTF(), input.uuid(), input.uuid()) else null
                if (input.read() != -1) throw IOException()
                result
            }
        } finally { plain.fill(0) }
    }
    @Synchronized fun write(destination: DriveDestination?) = safe {
        val plain = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeUTF(DOMAIN); output.writeBoolean(destination != null)
            destination?.let { output.writeUTF(it.accountName); output.uuid(it.configurationId); output.uuid(it.sourceId) }
        } }.toByteArray()
        try { bytes.writeAtomically(protector.seal(plain)) } finally { plain.fill(0) }
    }
    internal fun current(queue: EncryptedSnapshotQueue, id: UUID): DriveDestination? = synchronized(this) {
        read()?.takeIf { it.configurationId == id && queue.activeConfigurationId() == id && queue.sourceId() == it.sourceId }
    }
    internal fun <T> ifCurrent(queue: EncryptedSnapshotQueue, destination: DriveDestination, block: () -> T): T = synchronized(this) {
        if (current(queue, destination.configurationId) != destination) throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
        block()
    }
    private fun <T> safe(block: () -> T): T = try { block() } catch (e: DriveConnectionException) { throw e }
        catch (_: Exception) { throw DriveConnectionException(DriveConnectionFailure.STORAGE) }
    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    companion object { private const val DOMAIN = "OpenAuth/drive-destination/v1" }
}

/** initialSnapshot создаёт ciphertext в разблокированной сессии; worker этой зависимости не получает. */
class DriveConnectionController(
    private val queue: EncryptedSnapshotQueue,
    private val destinations: DriveDestinationStore,
    val status: DriveSyncStatus,
    private val schedule: (UUID) -> Unit,
    private val cancel: () -> Unit,
    private val initialSnapshot: suspend () -> Unit,
) {
    suspend fun destination(): DriveDestination? = withContext(Dispatchers.IO) {
        destinations.read()?.let { destinations.current(queue, it.configurationId) }
    }
    suspend fun refreshStatus(): Unit = withContext(Dispatchers.IO) {
        synchronized(destinations) {
            val current = destinations.read()?.let { destinations.current(queue, it.configurationId) }
            if (current == null) status.publish(null, DriveSyncPhase.DISCONNECTED)
            else if (status.state.value.configurationId != current.configurationId) {
                val checkpoint = queue.checkpoint()
                status.publish(current.configurationId, when {
                    checkpoint?.uploaded == true -> DriveSyncPhase.UPLOADED
                    checkpoint != null -> DriveSyncPhase.PENDING
                    else -> DriveSyncPhase.WAITING_FOR_UNLOCK
                })
            }
        }
    }
    suspend fun connect(accountName: String): DriveDestination = withContext(Dispatchers.IO) {
        val next = synchronized(destinations) {
            val value = DriveDestination(accountName, UUID.randomUUID(), queue.sourceId())
            // Между двумя durable stores нет транзакции: сначала инвалидируем очередь. Любой сбой закрывает отправку.
            deletionLeaseInvalidated()
            queue.setActiveConfiguration(null)
            cancel()
            destinations.write(value)
            queue.setActiveConfiguration(value.configurationId)
            status.publish(value.configurationId, DriveSyncPhase.WAITING_FOR_UNLOCK)
            value
        }
        prepareSnapshot(next)
        next
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(destinations) {
            deletionLeaseInvalidated()
            queue.setActiveConfiguration(null)
            cancel()
            destinations.write(null)
            status.publish(null, DriveSyncPhase.DISCONNECTED)
        }
    }
    suspend fun uploadNow() = withContext(Dispatchers.IO) {
        val destination = destination() ?: throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
        prepareSnapshot(destination)
    }
    suspend fun resumeUploads() = withContext(Dispatchers.IO) {
        val destination = destination() ?: throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
        if (queue.latest() == null) prepareSnapshot(destination)
        else destinations.ifCurrent(queue, destination) {
            status.publish(destination.configurationId, DriveSyncPhase.PENDING)
            schedule(destination.configurationId)
        }
    }
    private fun deletionLeaseInvalidated() { destinations.deletionLease = null }
    private suspend fun prepareSnapshot(destination: DriveDestination) {
        try {
            destinations.ifCurrent(queue, destination) { }
            initialSnapshot()
            destinations.ifCurrent(queue, destination) {
                val pending = queue.latest()
                status.publish(destination.configurationId, if (pending == null) DriveSyncPhase.NEEDS_BACKUP_PASSWORD else DriveSyncPhase.PENDING)
                if (pending != null) schedule(destination.configurationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BackupFlowException) {
            destinations.ifCurrent(queue, destination) {
                status.publish(destination.configurationId, when (failure.reason) {
                    BackupFlowFailure.LOCKED_OR_CHANGED -> DriveSyncPhase.WAITING_FOR_UNLOCK
                    BackupFlowFailure.NOT_CONFIGURED -> DriveSyncPhase.NEEDS_BACKUP_PASSWORD
                    else -> DriveSyncPhase.FAILED
                })
            }
        }
    }
}
