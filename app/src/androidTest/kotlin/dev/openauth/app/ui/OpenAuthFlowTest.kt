package dev.openauth.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.openauth.app.R
import dev.openauth.app.scanner.QrImportFlow
import dev.openauth.app.vault.*
import dev.openauth.core.model.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяет настоящие Compose callbacks и vault mutations; системная аутентификация — отдельный тест. */
@RunWith(AndroidJUnit4::class)
class OpenAuthFlowTest {
    @get:Rule val compose = createComposeRule()
    private val storage = object : VaultStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private val keys = object : VaultKeyProtector {
        override fun prepare(create: Boolean) = Unit
        override fun wrap(key: ByteArray) = key.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }
    private val repository = VaultRepository(storage, keys, Dispatchers.Unconfined)
    private val model = OpenAuthViewModel(repository)
    private fun snapshot() = (repository.state.value as VaultState.Unlocked).snapshot

    @Test fun manualAddAppearsAsCorrectCodeAndLockHidesItImmediately() {
        runBlocking { repository.initialize() }
        compose.setContent { OpenAuthApp(model, onUnlock = {}, clock = OtpClock { 59 }) }
        compose.onNodeWithTag("add-account").performClick()
        compose.onNodeWithTag("editor-account").performTextInput("alice@example.org")
        compose.onNodeWithTag("editor-secret").performTextInput("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        compose.onNodeWithTag("editor-save").performScrollTo().performClick()
        compose.waitUntil(5000) { snapshot().entries.size == 1 }
        val id = snapshot().entries.single().id
        compose.waitUntil(5000) { compose.onAllNodesWithText("287 082").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("code-$id").assertTextContains("287 082")
        compose.runOnIdle { repository.lock() }
        compose.onNodeWithTag("code-$id").assertDoesNotExist()
        compose.onNodeWithTag("unlock-vault").assertIsDisplayed()
    }

    @Test fun scannerCancelDiscardsPartialMigrationBeforeStartingAnotherBatch() = verifyScannerExit(ScannerExit.CANCEL)
    @Test fun scannerSystemBackDiscardsPartialMigrationAndReturnsToCodes() = verifyScannerExit(ScannerExit.BACK)
    @Test fun scannerManualFallbackDiscardsPartialMigration() = verifyScannerExit(ScannerExit.MANUAL)

    private enum class ScannerExit { CANCEL, BACK, MANUAL }
    private fun verifyScannerExit(exit: ScannerExit) {
        runBlocking { repository.initialize() }
        val flow = QrImportFlow()
        val batchA0 = "otpauth-migration://offline?data=Cj4KFDAxMjM0NTY3ODkwMTIzNDU2Nzg5EhVhbGljZUBleGFtcGxlLmludmFsaWQaB0V4YW1wbGUgASgBMAI4ABABGAIgACgq"
        val batchB0 = batchA0.dropLast(1) + "r"
        val batchB1 = "otpauth-migration://offline?data=CkQKFDAxMjM0NTY3ODkwMTIzNDU2Nzg5EhNib2JAZXhhbXBsZS5pbnZhbGlkGgdFeGFtcGxlIAEoATABOP%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FfxABGAIgASgr"
        flow.accept(batchA0)
        assertTrue(flow.waitingForParts)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Отдельный camera-denied тест проверяет fallback; здесь изолируем фактический callback Cancel.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        compose.setContent { OpenAuthApp(model, onUnlock = {}, clock = OtpClock { 59 }, importFlow = flow) }
        compose.onNodeWithTag("scan-account").performClick()
        val cancel = context.getString(R.string.cancel)
        compose.waitUntil(5000) {
            try { compose.onAllNodesWithText(cancel).fetchSemanticsNodes().isNotEmpty() }
            catch (_: IllegalStateException) { false }
        }
        when (exit) {
            ScannerExit.CANCEL -> compose.onNodeWithText(cancel).performClick()
            ScannerExit.BACK -> UiDevice.getInstance(instrumentation).pressBack()
            ScannerExit.MANUAL -> {
                compose.onNodeWithText(context.getString(R.string.manual_entry)).performClick()
                compose.onNodeWithTag("editor-account").assertIsDisplayed()
                compose.onNodeWithText(cancel).performScrollTo().performClick()
            }
        }
        compose.onNodeWithTag("scan-account").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse("Отмена сканера должна забыть предыдущую часть A", flow.waitingForParts)
            flow.accept(batchB0)
            flow.accept(batchB1)
            assertEquals(listOf("alice@example.invalid", "bob@example.invalid"), flow.preview.map { it.accountName })
            assertTrue(snapshot().entries.isEmpty())
        }
    }

    @Test fun deleteKeepsAccountUntilConfirmation() {
        runBlocking {
            repository.initialize()
            model.save(AccountDraft(issuer = "Example", accountName = "alice", secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        }
        val id = snapshot().entries.single().id
        compose.setContent { OpenAuthApp(model, onUnlock = {}, clock = OtpClock { 59 }) }
        compose.onNodeWithTag("edit-$id").performClick()
        compose.onNodeWithTag("editor-delete").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, snapshot().entries.size) }
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitUntil(5000) { snapshot().entries.isEmpty() }
    }
}
