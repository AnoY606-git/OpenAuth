package dev.openauth.app.backup

import java.io.File
import java.util.UUID

sealed interface BackupState {
    data object NeverCreated : BackupState
    data class Pending(val snapshot: PendingSnapshot) : BackupState
    data class Uploaded(val snapshot: PendingSnapshot) : BackupState
    data class Failure(val reason: BackupFlowFailure) : BackupState
}

enum class BackupFlowFailure { NOT_CONFIGURED, LOCKED_OR_CHANGED, STALE_PREVIEW, STORAGE, INVALID_FILE, DESTINATION_CHANGED, BUSY }
class BackupFlowException(val reason: BackupFlowFailure) : Exception(reason.name)

/** Только непрозрачные идентификаторы и шифротекст; ключей и содержимого аккаунтов здесь нет. */
data class PendingSnapshot(
    val snapshotId: UUID,
    val sourceId: UUID,
    val revision: Long,
    val createdAtEpochSeconds: Long,
    val configurationId: UUID,
    val file: File,
    val remoteFileId: String? = null,
    val generationId: String,
)

data class SnapshotCheckpoint(val snapshot: PendingSnapshot, val uploaded: Boolean)
