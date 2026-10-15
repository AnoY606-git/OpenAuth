package dev.openauth.app.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupFileSessionTest {
    @Test fun cloudCiphertextIsDefensivelyCopiedAndUsesCommonImportReader() = runBlocking {
        val session = BackupFileSession()
        val input = ByteArray(176) { 42 }
        session.stageCiphertext(input)
        input.fill(0)
        val first = session.readImport { error("Cloud import must not read SAF") }
        assertEquals(42.toByte(), first[0])
        first.fill(1)
        assertEquals(42.toByte(), session.readImport { error("Unexpected SAF") }[0])
        session.clearImport()
        assertNull(session.pendingImport.value)
        try { session.readImport { error("Unexpected SAF") }; fail("No pending import") }
        catch (expected: BackupFlowException) { assertEquals(BackupFlowFailure.INVALID_FILE, expected.reason) }
    }
    @Test fun oversizedReplacementClearsPreviousPendingImport() {
        val session = BackupFileSession()
        session.stageCiphertext(ByteArray(176))
        try { session.stageCiphertext(ByteArray(FileBackupGateway.MAX_BYTES + 1)); fail("Must reject oversized input") }
        catch (expected: BackupFlowException) { assertEquals(BackupFlowFailure.INVALID_FILE, expected.reason) }
        assertNull(session.pendingImport.value)
    }
}
