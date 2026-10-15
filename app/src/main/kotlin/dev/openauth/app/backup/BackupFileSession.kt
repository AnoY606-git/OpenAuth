package dev.openauth.app.backup

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Источник существует только в памяти процесса и не содержит пароля или открытого снимка. */
sealed interface BackupImportSource {
    class Document(val uri: Uri) : BackupImportSource
    class Ciphertext internal constructor(bytes: ByteArray) : BackupImportSource {
        private val bytes = bytes.copyOf()
        internal fun copyBytes() = bytes.copyOf()
    }
}

/** Живёт вместе с coordinator: только шифротекст/URI переживают блокировку и удаление composable. */
class BackupFileSession {
    private var exportBytes: ByteArray? = null
    private val mutableImport = MutableStateFlow<BackupImportSource?>(null)
    val pendingImport = mutableImport.asStateFlow()
    @Synchronized fun stageExport(bytes: ByteArray) { exportBytes = bytes.copyOf() }
    @Synchronized fun takeExport(): ByteArray? = exportBytes.also { exportBytes = null }
    @Synchronized fun stageImport(uri: Uri?) { mutableImport.value = uri?.let(BackupImportSource::Document) }
    @Synchronized fun stageCiphertext(bytes: ByteArray) {
        mutableImport.value = null
        if (bytes.size !in 176..FileBackupGateway.MAX_BYTES) throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
        mutableImport.value = BackupImportSource.Ciphertext(bytes)
    }
    suspend fun readImport(readDocument: suspend (Uri) -> ByteArray): ByteArray = when (val source = mutableImport.value) {
        is BackupImportSource.Document -> readDocument(source.uri)
        is BackupImportSource.Ciphertext -> source.copyBytes()
        null -> throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
    }
    @Synchronized fun clearImport() { mutableImport.value = null }
}
