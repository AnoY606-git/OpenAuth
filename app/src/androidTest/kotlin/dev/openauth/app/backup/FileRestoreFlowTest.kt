package dev.openauth.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Настоящие Android Keystore и файловая очередь; системный SAF picker проверяется отдельно на устройстве. */
@RunWith(AndroidJUnit4::class)
class FileRestoreFlowTest {
    @Test fun ciphertextQueueReopensWithNonAuthenticatedMetadataKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "queue-test-${UUID.randomUUID()}")
        try {
            val protector = AndroidMetadataProtector("OpenAuth.test.metadata.${UUID.randomUUID()}")
            val queue = EncryptedSnapshotQueue(directory, protector)
            queue.setActiveConfiguration(UUID.randomUUID())
            val ciphertext = ByteArray(1024) { it.toByte() }
            val record = queue.enqueue(ciphertext, 7, "synthetic-generation", 123)
            val claim = queue.claim(record.configurationId)!!
            queue.assignRemoteFileId(claim, "synthetic_remote_id")
            claim.close()
            val reopened = EncryptedSnapshotQueue(directory, protector)
            assertEquals("synthetic_remote_id", reopened.latest()!!.remoteFileId)
            assertArrayEquals(ciphertext, reopened.latest()!!.file.readBytes())
            assertFalse(File(directory, "metadata").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic_remote_id"))
        } finally { directory.deleteRecursively() }
    }
}
