package dev.openauth.app.drive

import dev.openauth.app.backup.EncryptedSnapshotQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Выбор привязан к назначению, при смене аккаунта прежний список недействителен. */
data class DriveHistory(val destination: DriveDestination, val snapshots: List<DriveSnapshot>)

class DriveRestoreBrowser(
    private val queue: EncryptedSnapshotQueue,
    private val destinations: DriveDestinationStore,
    private val tokens: DriveTokenProvider,
    private val client: DriveRestClient,
    private val status: DriveSyncStatus? = null,
    private val cancelUploads: () -> Unit = {},
) {
    suspend fun list(): DriveHistory = withContext(Dispatchers.IO) {
        val destination = destinations.read()?.let { destinations.current(queue, it.configurationId) }
            ?: throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
        checkCurrent(destination)
        val token = token(destination)
        checkCurrent(destination)
        // История восстановления включает другие телефоны этого аккаунта.
        val snapshots = request(destination, token, { checkCurrent(destination) }) { client.listSnapshots(token) }
        checkCurrent(destination)
        DriveHistory(destination, snapshots)
    }
    suspend fun download(history: DriveHistory, snapshot: DriveSnapshot): ByteArray = withContext(Dispatchers.IO) {
        if (snapshot !in history.snapshots) throw DriveException(DriveFailure.INVALID_REQUEST)
        val destination = history.destination
        checkCurrent(destination)
        val token = token(destination)
        checkCurrent(destination)
        val ciphertext = request(destination, token, { checkCurrent(destination) }) { client.download(token, snapshot.fileId) }
        try { checkCurrent(destination); ciphertext }
        catch (failure: Exception) { ciphertext.fill(0); throw failure }
    }
    /** Вызывать только после отдельного подтверждения UI: операция отключает автоматические копии. */
    suspend fun deleteHistory(expected: DriveDestination) = withContext(Dispatchers.IO) {
        val lease = java.util.UUID.randomUUID()
        destinations.ifCurrent(queue, expected) {
            // Инвалидация и отмена происходят сразу, даже если HTTP ещё выполняется.
            queue.setActiveConfiguration(null)
            cancelUploads()
            destinations.write(null)
            destinations.deletionLease = lease
            status?.publish(null, DriveSyncPhase.DISCONNECTED)
        }
        fun checkDisabled() {
            synchronized(destinations) {
                if (destinations.deletionLease != lease || destinations.read() != null || queue.activeConfigurationId() != null) {
                    throw DriveConnectionException(DriveConnectionFailure.DESTINATION_CHANGED)
                }
            }
        }
        try {
            // Барьер ждёт выхода старого engine. Connect/disconnect не ждут этот gate,
            // а отменяют lease: старое удаление не получает токен нового аккаунта.
            destinations.operations.withLock {
                checkDisabled()
                val token = token(expected)
                checkDisabled()
                val snapshots = request(expected, token, ::checkDisabled) { client.listSnapshots(token, expected.sourceId) }
                checkDisabled()
                for (snapshot in snapshots) {
                    checkDisabled()
                    request(expected, token, ::checkDisabled) { client.deleteSnapshot(token, snapshot.fileId, expected.sourceId) }
                    checkDisabled()
                }
            }
        } finally {
            synchronized(destinations) { if (destinations.deletionLease == lease) destinations.deletionLease = null }
        }
    }
    /** UI повторяет операцию явно; очищаем именно отклонённый token перед этой следующей попыткой. */
    private suspend fun <T> request(destination: DriveDestination, token: String, check: () -> Unit, block: suspend () -> T): T {
        check()
        try { return block() }
        catch (failure: DriveException) {
            check()
            if (failure.failure == DriveFailure.AUTH_REQUIRED) {
                tokens.invalidate(destination.accountName, token)
                check()
            }
            throw failure
        }
    }
    private fun checkCurrent(destination: DriveDestination) { destinations.ifCurrent(queue, destination) { } }
    private suspend fun token(destination: DriveDestination): String = when (val access = tokens.authorize(destination.accountName)) {
        is DriveAccess.Granted -> if (access.accountName == destination.accountName && access.token.isNotBlank()) access.token else throw DriveException(DriveFailure.AUTH_REQUIRED)
        DriveAccess.Unavailable -> throw DriveConnectionException(DriveConnectionFailure.UNAVAILABLE)
        is DriveAccess.Failure -> when (access.reason) {
            DriveOAuthFailure.RETRYABLE -> throw DriveException(DriveFailure.RETRYABLE)
            DriveOAuthFailure.CONFIGURATION -> throw DriveConnectionException(DriveConnectionFailure.AUTHORIZATION)
            DriveOAuthFailure.CANCELLED, DriveOAuthFailure.DENIED -> throw DriveException(DriveFailure.AUTH_REQUIRED)
        }
        else -> throw DriveException(DriveFailure.AUTH_REQUIRED)
    }
}
