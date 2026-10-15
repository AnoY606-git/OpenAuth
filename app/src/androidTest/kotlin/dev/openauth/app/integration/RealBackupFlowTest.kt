package dev.openauth.app.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.openauth.app.MainActivity
import dev.openauth.app.OpenAuthApplication
import dev.openauth.app.R
import dev.openauth.app.ui.AccountDraft
import dev.openauth.app.ui.OpenAuthViewModel
import dev.openauth.app.vault.VaultState
import dev.openauth.core.otp.OtpEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Реальный системный PIN, Keystore и DocumentsUI. Запускать на одноразовом тестовом AVD. */
@RunWith(AndroidJUnit4::class)
class RealBackupFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as OpenAuthApplication
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()
    private fun awaitText(id: Int) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }
    private fun credential(pin: String) {
        val input = device.wait(Until.findObject(By.res("com.android.systemui", "lockPassword")), 10_000)
        assertNotNull("Ожидается настоящее системное окно PIN", input)
        input.text = pin; device.pressEnter()
        compose.waitUntil(10_000) { app.vault.state.value is VaultState.Unlocked }
    }
    private fun snapshot() = (app.vault.state.value as VaultState.Unlocked).snapshot
    private fun password(value: String) {
        compose.onNode(hasText(text(R.string.backup_password_label)) and hasSetTextAction()).performTextInput(value)
        compose.onNodeWithText(text(R.string.backup_continue)).performClick()
    }

    @Test fun corruptVaultRecoveryPreservesCiphertextAndAuthenticatesNewKey() {
        val pin = requireNotNull(InstrumentationRegistry.getArguments().getString("testPin")) { "Real PIN required" }
        compose.onNodeWithTag("unlock-vault").performClick(); credential(pin)
        val model = OpenAuthViewModel(app.vault)
        val label = "Recovery synthetic ${System.currentTimeMillis()}"
        runBlocking { model.save(AccountDraft(issuer = "Recovery", accountName = label, secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")) }
        val expectedCode = OtpEngine.totp(snapshot().entries.single { it.accountName == label }, 59)
        val ciphertext = runBlocking {
            app.backup.configure(dev.openauth.core.backup.BackupCredential.password("Synthetic recovery password 2026!".toCharArray()))
            app.backup.exportSnapshot()
        }
        app.vault.lock()
        val file = java.io.File(app.noBackupFilesDir, "vault.oav")
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        runBlocking { try { app.vault.unlock(); fail("Damaged ciphertext opened") } catch (_: dev.openauth.app.vault.VaultException) {} }
        app.backup.files.stageCiphertext(ciphertext)
        compose.onAllNodesWithText(text(R.string.backups)).onLast().performClick()
        click(R.string.backup_recover_unavailable)
        password("Synthetic recovery password 2026!")
        awaitText(R.string.backup_preview_title)
        assertArrayEquals(corrupted, file.readBytes())
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_restore_confirm)).performClick()
        credential(pin)
        awaitText(R.string.backup_restore_success)
        val saved = java.io.File(app.noBackupFilesDir, "vault-recovery").listFiles().orEmpty()
        assertTrue("Original ciphertext retained privately", saved.any { it.readBytes().contentEquals(corrupted) })
        assertEquals(expectedCode, OtpEngine.totp(snapshot().entries.single { it.accountName == label }, 59))
        app.vault.lock()
        compose.onAllNodesWithText(text(R.string.codes)).onLast().performClick()
        compose.onNodeWithTag("unlock-vault").performClick(); credential(pin)
        assertEquals(expectedCode, OtpEngine.totp(snapshot().entries.single { it.accountName == label }, 59))
    }

    @Test fun actualSafRoundTripRequiresUnlockAndRejectsWrongPasswordBeforeCommit() {
        val pin = InstrumentationRegistry.getArguments().getString("testPin")
        assumeNotNull(pin)
        compose.onNodeWithTag("unlock-vault").performClick(); credential(pin!!)
        val model = OpenAuthViewModel(app.vault)
        val label = "SAF synthetic ${java.util.UUID.randomUUID()}"
        // Генератор уникален для прогона: MERGE вправе сохранить метаданные существующего дубликата.
        val syntheticSecret = dev.openauth.core.otp.Base32.encode(dev.openauth.core.model.OtpSecret.fromBytes(
            java.security.MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8)).copyOf(20),
        ))
        runBlocking { model.save(AccountDraft(issuer = "Test service", accountName = label, secret = syntheticSecret)) }
        val entry = snapshot().entries.single { it.accountName == label }
        val expectedCode = OtpEngine.totp(entry, 59)
        compose.onAllNodesWithText(text(R.string.backups)).onLast().performClick()
        click(R.string.backup_configure)
        awaitText(R.string.backup_set_password)
        password("Synthetic backup password 2026!")
        awaitText(R.string.backup_configured)
        click(R.string.backup_export)
        val fileName = "OpenAuth-integration-${System.currentTimeMillis()}.oab"
        val nameInput = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull("DocumentsUI filename input", nameInput)
        nameInput.text = fileName
        val save = device.wait(Until.findObject(By.res("com.google.android.documentsui", "button1")), 2_000)
            ?: device.wait(Until.findObject(By.res("android", "button1")), 2_000)
            ?: device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("SAVE|Save|СОХРАНИТЬ|Сохранить"))), 5_000)
        assertNotNull("DocumentsUI save action", save); save.click()
        awaitText(R.string.backup_export_success)
        assertEquals("SAF must leave vault locked", VaultState.Locked, app.vault.state.value)
        // Повторное явное действие открывает настоящий системный prompt; старый grant не открывает vault.
        click(R.string.backup_configure); credential(pin)
        awaitText(R.string.backup_set_password)
        compose.onNodeWithText(text(R.string.backup_cancel)).performClick()
        runBlocking { model.requestDelete(entry.id); model.confirmDelete(entry.id) }
        val before = snapshot()
        click(R.string.backup_open)
        val selected = device.wait(Until.findObject(By.text(fileName)), 10_000)
        assertNotNull("Saved file must be visible in actual DocumentsUI", selected); selected.click()
        awaitText(R.string.backup_file_selected)
        assertEquals(VaultState.Locked, app.vault.state.value)
        click(R.string.backup_continue_restore); credential(pin)
        awaitText(R.string.backup_restore_password)
        password("Wrong synthetic password 2026!")
        awaitText(R.string.backup_credential_or_file_error)
        assertEquals(before.entries, snapshot().entries)
        click(R.string.backup_continue_restore)
        awaitText(R.string.backup_restore_password)
        password("Synthetic backup password 2026!")
        awaitText(R.string.backup_preview_title)
        assertFalse(snapshot().entries.any { it.id == entry.id })
        compose.onNodeWithText(text(R.string.backup_restore_confirm)).performClick()
        awaitText(R.string.backup_restore_success)
        val restored = snapshot().entries.single { it.accountName == label }
        assertEquals(expectedCode, OtpEngine.totp(restored, 59))
    }
}
