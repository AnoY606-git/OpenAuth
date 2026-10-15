package dev.openauth.app.backup

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class FileBackupGatewayTest {
    @Test fun rejectsOversizedDocumentsBeforeDecrypting() {
        assertThrows(BackupFlowException::class.java) {
            FileBackupGateway.readBounded(ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1)))
        }
    }
    @Test fun readsCompleteCiphertextWithoutDependingOnAvailable() {
        val bytes = ByteArray(9876) { it.toByte() }
        val stream = object : ByteArrayInputStream(bytes) { override fun available() = 0 }
        assertArrayEquals(bytes, FileBackupGateway.readBounded(stream))
    }
}
