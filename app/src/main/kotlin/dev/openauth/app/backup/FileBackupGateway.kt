package dev.openauth.app.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Потоки SAF открываются только на время чтения/записи; grant и plaintext не сохраняются. */
class FileBackupGateway(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use(::readBounded) ?: throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
    }
    suspend fun write(uri: Uri, ciphertext: ByteArray) = withContext(Dispatchers.IO) {
        if (ciphertext.size !in 176..MAX_BYTES) throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
        resolver.openOutputStream(uri, "wt")?.use { it.write(ciphertext); it.flush() }
            ?: throw BackupFlowException(BackupFlowFailure.STORAGE)
    }
    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val next = input.read(); if (next < 0) break
                    if (output.size() == MAX_BYTES) throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
                    output.write(next); continue
                }
                if (count > MAX_BYTES - output.size()) throw BackupFlowException(BackupFlowFailure.INVALID_FILE)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
