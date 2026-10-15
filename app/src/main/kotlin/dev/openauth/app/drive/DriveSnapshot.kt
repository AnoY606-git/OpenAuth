package dev.openauth.app.drive

import java.util.UUID

/** Метаданные непрозрачной зашифрованной копии; содержимое проверяет backup codec. */
data class DriveSnapshot(
    val fileId: String,
    val snapshotId: UUID,
    val sourceId: UUID,
    val createdAtEpochSeconds: Long,
    val revision: Long,
    val sizeBytes: Long,
)
