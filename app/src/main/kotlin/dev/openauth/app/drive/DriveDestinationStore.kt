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
data class DriveSyncState(val configurationId: UUID? = null, val phase: DriveSyncPhase = DriveSyncPhase.DISCONNECTED, val lastUploadedAtEpochSeconds: Long? = null)
class DriveSyncStatus {
    private val mutableState = MutableStateFlow(DriveSyncState())
    val state = mutableState.asStateFlow()
    internal fun publish(configuration: UUID?, phase: DriveSyncPhase, confirmedAt: Long? = mutableState.value.takeIf { it.configurationId == configuration }?.lastUploadedAtEpochSeconds) { mutableState.value = DriveSyncState(configuration, phase, confirmedAt) }
}

/** Тот же экземпляр служит короткой блокировкой публикации/configuration; сетевых вызовов под ней нет. */
class DriveDestinationStore(private val bytes: AtomicBytes, private val protector: MetadataProtector) {
    internal val operations = Mutex()
    internal var deletionLease: UUID? = null
    private data class Record(val destination: DriveDestination?, val confirmedAt: Long?)
    @Synchronized fun read(): DriveDestination? = readRecord().destination
    @Synchronized fun lastUploadedAt(configurationId: UUID): Long? = readRecord().let {
        if (it.destination?.configurationId == configurationId) it.confirmedAt else null
    }
    @Synchronized fun confirmUpload(destination: DriveDestination, instant: Long) {
        require(instant >= 0)
        val current = readRecord()
        if (current.destination != destination) throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
        writeRecord(Record(destination, instant))
    }
    private fun readRecord(): Record = safe {
        val encrypted = bytes.read() ?: return@safe Record(null, null)
        val plain = protector.open(encrypted)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                if (plain.size > 2048) throw IOException()
                val domain = input.readUTF()
                if (domain != DOMAIN && domain != LEGACY_DOMAIN) throw IOException()
                val destination = if (input.readBoolean()) DriveDestination(input.readUTF(), input.uuid(), input.uuid()) else null
                val instant = if (domain == DOMAIN && input.readBoolean()) input.readLong().also { if (it < 0) throw IOException() } else null
                if (input.read() != -1 || (destination == null && instant != null)) throw IOException()
                Record(destination, instant)
            }
        } finally { plain.fill(0) }
    }
    @Synchronized fun write(destination: DriveDestination?) {
        val previous = readRecord()
        writeRecord(Record(destination, previous.confirmedAt.takeIf { destination != null && destination == previous.destination }))
    }
    private fun writeRecord(record: Record) = safe {
        val plain = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeUTF(DOMAIN); output.writeBoolean(record.destination != null)
            record.destination?.let { output.writeUTF(it.accountName); output.uuid(it.configurationId); output.uuid(it.sourceId) }
            output.writeBoolean(record.confirmedAt != null); record.confirmedAt?.let(output::writeLong)
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
    companion object { private const val LEGACY_DOMAIN = "OpenAuth/drive-destination/v1"; private const val DOMAIN = "OpenAuth/drive-destination/v2" }
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
        destinations.read()
    }
    suspend fun refreshStatus(): Unit = withContext(Dispatchers.IO) {
        synchronized(destinations) {
            val stored = destinations.read()
            val current = stored?.let { destinations.current(queue, it.configurationId) }
            if (stored != null && current == null) status.publish(stored.configurationId, DriveSyncPhase.FAILED, destinations.lastUploadedAt(stored.configurationId))
            else if (current == null) status.publish(null, DriveSyncPhase.DISCONNECTED)
            else if (status.state.value.configurationId != current.configurationId) {
                val checkpoint = queue.checkpoint()
                status.publish(current.configurationId, when {
                    checkpoint?.uploaded == true -> DriveSyncPhase.UPLOADED
                    checkpoint != null -> DriveSyncPhase.PENDING
                    else -> DriveSyncPhase.WAITING_FOR_UNLOCK
                }, destinations.lastUploadedAt(current.configurationId))
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
    /** Сначала останавливаем локальную отправку, затем отзываем grant строго выбранного аккаунта. */
    suspend fun revoke(selected: DriveDestination, revokeAccess: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        synchronized(destinations) {
            if (destinations.read() != selected) throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
            deletionLeaseInvalidated()
            queue.setActiveConfiguration(null)
            cancel()
            status.publish(selected.configurationId, DriveSyncPhase.NEEDS_CONSENT)
        }
        try {
            revokeAccess(selected.accountName)
            synchronized(destinations) {
                if (destinations.read() != selected) throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
                destinations.write(null)
                status.publish(null, DriveSyncPhase.DISCONNECTED)
            }
        } catch (failure: Exception) {
            synchronized(destinations) {
                if (destinations.read() == selected) status.publish(selected.configurationId, DriveSyncPhase.FAILED)
            }
            throw failure
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
