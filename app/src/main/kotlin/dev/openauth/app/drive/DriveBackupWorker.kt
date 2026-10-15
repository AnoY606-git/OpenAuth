package dev.openauth.app.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.openauth.app.backup.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Граница HTTP нужна для проверки гонок; production адаптер отправляет исходный ciphertext. */
interface DriveRemote {
    suspend fun generateFileId(token: String): String
    suspend fun upload(token: String, snapshot: PendingSnapshot): DriveSnapshot
    suspend fun trimHistory(token: String, sourceId: UUID)
}
class DriveRestRemote(private val client: DriveRestClient) : DriveRemote {
    override suspend fun generateFileId(token: String) = client.generateFileId(token)
    override suspend fun upload(token: String, snapshot: PendingSnapshot) = client.upload(
        token, snapshot.file, snapshot.snapshotId, snapshot.sourceId, snapshot.revision,
        snapshot.remoteFileId ?: throw DriveException(DriveFailure.INVALID_REQUEST),
    )
    override suspend fun trimHistory(token: String, sourceId: UUID) = client.trimHistory(token, sourceId, 10)
}
enum class DriveRunResult { SUCCESS, RETRY, WAITING_FOR_USER, FAILURE, OBSOLETE }

/** Один engine на процесс. Ни vault, ни OTP, ни пароль/ключ backup сюда не передаются. */
class DriveUploadEngine(
    private val queue: EncryptedSnapshotQueue,
    private val destinations: DriveDestinationStore,
    private val tokens: DriveTokenProvider,
    private val remote: DriveRemote,
    private val status: DriveSyncStatus,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun run(configurationId: UUID): DriveRunResult = withContext(Dispatchers.IO) { destinations.operations.withLock {
        var destination: DriveDestination? = null
        try {
            val captured = destinations.current(queue, configurationId) ?: return@withLock DriveRunResult.OBSOLETE
            destination = captured
            if (queue.latest() == null) {
                publish(captured, if (queue.checkpoint()?.uploaded == true) DriveSyncPhase.UPLOADED else DriveSyncPhase.WAITING_FOR_UNLOCK)
                return@withLock DriveRunResult.SUCCESS
            }
            var token = authorizeToken(captured)
            var refreshed = false
            suspend fun <T> authenticated(operation: suspend (String) -> T): T {
                while (true) {
                    checkCurrent(captured)
                    try {
                        val result = operation(token)
                        checkCurrent(captured)
                        return result
                    } catch (failure: DriveException) {
                        checkCurrent(captured)
                        if (failure.failure != DriveFailure.AUTH_REQUIRED) throw failure
                        // 401 относится к этому token, не ко всем grants. Даже второй отклонённый
                        // token очищается, чтобы следующая явная попытка не взяла его из кеша.
                        tokens.invalidate(captured.accountName, token)
                        checkCurrent(captured)
                        if (refreshed) throw failure
                        refreshed = true
                        token = authorizeToken(captured)
                    }
                }
            }
            // Ограничиваем одну задачу; следующее звено WorkManager подхватит непрерывный поток изменений.
            repeat(8) {
                checkCurrent(captured)
                val claim = queue.claim(configurationId) ?: return@withLock DriveRunResult.SUCCESS
                claim.use {
                    publish(captured, DriveSyncPhase.UPLOADING)
                    if (claim.snapshot.remoteFileId == null) {
                        checkCurrent(captured)
                        val id = authenticated { remote.generateFileId(it) }
                        checkCurrent(captured)
                        destinations.ifCurrent(queue, captured) { queue.assignRemoteFileId(claim, id) }
                    }
                    checkCurrent(captured)
                    val snapshot = claim.snapshot
                    val uploaded = authenticated { remote.upload(it, snapshot) }
                    checkCurrent(captured)
                    if (uploaded.fileId != snapshot.remoteFileId || uploaded.snapshotId != snapshot.snapshotId ||
                        uploaded.sourceId != snapshot.sourceId || uploaded.revision != snapshot.revision || uploaded.sizeBytes != snapshot.file.length()) {
                        throw DriveException(DriveFailure.CONFLICT)
                    }
                    checkCurrent(captured)
                    destinations.ifCurrent(queue, captured) {
                        destinations.confirmUpload(captured, now())
                        status.publish(configurationId, DriveSyncPhase.UPLOADING, destinations.lastUploadedAt(configurationId))
                    }
                    authenticated { remote.trimHistory(it, captured.sourceId) }
                    checkCurrent(captured)
                    destinations.ifCurrent(queue, captured) {
                        queue.markUploaded(claim)
                        status.publish(configurationId, if (queue.latest() == null) DriveSyncPhase.UPLOADED else DriveSyncPhase.PENDING)
                    }
                }
            }
            if (queue.latest() == null) DriveRunResult.SUCCESS else DriveRunResult.RETRY
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is AuthorizationStopped) return@withLock failure.result
            val captured = destination ?: return@withLock DriveRunResult.FAILURE
            val phase: DriveSyncPhase
            val outcome: DriveRunResult
            when (failure) {
                is DriveConnectionException -> if (failure.failure == DriveConnectionFailure.DESTINATION_CHANGED) return@withLock DriveRunResult.OBSOLETE
                is BackupFlowException -> if (failure.reason == BackupFlowFailure.DESTINATION_CHANGED) return@withLock DriveRunResult.OBSOLETE
            }
            when ((failure as? DriveException)?.failure) {
                DriveFailure.AUTH_REQUIRED, DriveFailure.PERMISSION_DENIED -> { phase = DriveSyncPhase.NEEDS_CONSENT; outcome = DriveRunResult.WAITING_FOR_USER }
                DriveFailure.NETWORK, DriveFailure.RETRYABLE, DriveFailure.RATE_LIMITED, DriveFailure.QUOTA, DriveFailure.AMBIGUOUS_UPLOAD -> {
                    phase = DriveSyncPhase.RETRYING; outcome = DriveRunResult.RETRY
                }
                else -> { phase = DriveSyncPhase.FAILED; outcome = DriveRunResult.FAILURE }
            }
            try { publish(captured, phase); outcome }
            catch (publication: DriveConnectionException) {
                if (publication.failure == DriveConnectionFailure.DESTINATION_CHANGED) DriveRunResult.OBSOLETE else DriveRunResult.FAILURE
            }
            catch (_: Exception) { DriveRunResult.FAILURE }
        }
    } }
    private class AuthorizationStopped(val result: DriveRunResult) : Exception(result.name)
    private suspend fun authorizeToken(destination: DriveDestination): String {
        checkCurrent(destination)
        val access = tokens.authorize(destination.accountName)
        checkCurrent(destination)
        return when (access) {
            is DriveAccess.Granted -> {
                if (access.accountName != destination.accountName || access.token.isBlank()) {
                    publish(destination, DriveSyncPhase.NEEDS_CONSENT)
                    throw AuthorizationStopped(DriveRunResult.WAITING_FOR_USER)
                }
                access.token
            }
            is DriveAccess.NeedsConsent, DriveAccess.Denied -> {
                publish(destination, DriveSyncPhase.NEEDS_CONSENT)
                throw AuthorizationStopped(DriveRunResult.WAITING_FOR_USER)
            }
            DriveAccess.Unavailable -> {
                publish(destination, DriveSyncPhase.UNAVAILABLE)
                throw AuthorizationStopped(DriveRunResult.WAITING_FOR_USER)
            }
            is DriveAccess.Failure -> when (access.reason) {
                DriveOAuthFailure.RETRYABLE -> throw DriveException(DriveFailure.RETRYABLE)
                DriveOAuthFailure.CONFIGURATION -> {
                    publish(destination, DriveSyncPhase.FAILED)
                    throw AuthorizationStopped(DriveRunResult.FAILURE)
                }
                DriveOAuthFailure.CANCELLED, DriveOAuthFailure.DENIED -> {
                    publish(destination, DriveSyncPhase.NEEDS_CONSENT)
                    throw AuthorizationStopped(DriveRunResult.WAITING_FOR_USER)
                }
            }
        }
    }
    private fun checkCurrent(destination: DriveDestination) { destinations.ifCurrent(queue, destination) { } }
    private fun publish(destination: DriveDestination, phase: DriveSyncPhase) = destinations.ifCurrent(queue, destination) { status.publish(destination.configurationId, phase, destinations.lastUploadedAt(destination.configurationId)) }
}

interface DriveWorkerOwner { val driveUploadEngine: DriveUploadEngine }
class DriveBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // Old persisted work can start before Application finishes cancelling the queue.
        if (!DriveFeature.enabled) return Result.success()
        val configuration = try { UUID.fromString(inputData.getString(CONFIGURATION_KEY)) } catch (_: Exception) { return Result.failure() }
        val engine = (applicationContext as? DriveWorkerOwner)?.driveUploadEngine ?: return Result.failure()
        return when (engine.run(configuration)) {
            DriveRunResult.SUCCESS, DriveRunResult.OBSOLETE, DriveRunResult.WAITING_FOR_USER -> Result.success()
            DriveRunResult.RETRY -> Result.retry()
            DriveRunResult.FAILURE -> Result.failure()
        }
    }
    companion object { const val CONFIGURATION_KEY = "configurationId" }
}
