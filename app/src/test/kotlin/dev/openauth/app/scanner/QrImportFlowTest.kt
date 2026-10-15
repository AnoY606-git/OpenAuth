package dev.openauth.app.scanner

import dev.openauth.app.ui.*
import dev.openauth.core.importing.ImportException
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.*
import org.junit.Test

class QrImportFlowTest {
    private val uri = "otpauth://totp/Example:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example"
    @Test fun invalidQrNeverCreatesPreview() {
        val flow = QrImportFlow()
        assertThrows(ImportException::class.java) { flow.accept("not a uri") }
        assertTrue(flow.preview.isEmpty())
    }
    @Test fun externalResultWaitsForUnlockThenRequiresExplicitPreviewConfirmation() {
        val flow = QrImportFlow()
        flow.deferUntilUnlocked(uri)
        assertTrue(flow.preview.isEmpty())
        flow.resumeAfterUnlock()
        assertEquals("alice", flow.preview.single().accountName)
        assertEquals("alice", flow.takeConfirmed().single().accountName)
        assertTrue(flow.preview.isEmpty())
    }
    @Test fun cancelClearsPendingSecretAndPreview() {
        val flow = QrImportFlow()
        flow.deferUntilUnlocked(uri); flow.clear(); flow.resumeAfterUnlock()
        assertTrue(flow.preview.isEmpty())
    }
    @Test fun cancelledIncompleteMigrationDoesNotPoisonNextBatch() {
        val root = File("../core/src/test/resources/migration")
        val first = File(root, "batch-0.uri").readText().trim()
        val second = File(root, "batch-1.uri").readText().trim()
        fun anotherBatch(uri: String): String {
            // Синтетический protobuf fixture заканчивается batch_id=42; меняем только его на43.
            val bytes = Base64.getDecoder().decode(URLDecoder.decode(uri.substringAfter("data="), UTF_8))
            bytes[bytes.lastIndex] = 43
            return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), UTF_8)
        }
        val flow = QrImportFlow()
        flow.accept(first)
        assertTrue(flow.waitingForParts)
        flow.clear()
        assertFalse(flow.waitingForParts)
        flow.accept(anotherBatch(first))
        assertTrue(flow.preview.isEmpty())
        flow.accept(anotherBatch(second))
        assertEquals(listOf("alice@example.invalid", "bob@example.invalid"), flow.preview.map { it.accountName })
    }
    @Test fun googleMigrationNeedsAllPartsBeforePreview() {
        val root = File("../core/src/test/resources/migration")
        val flow = QrImportFlow()
        flow.accept(File(root, "batch-0.uri").readText().trim())
        assertTrue(flow.preview.isEmpty())
        flow.accept(File(root, "batch-1.uri").readText().trim())
        assertTrue(flow.preview.isNotEmpty())
    }
}
