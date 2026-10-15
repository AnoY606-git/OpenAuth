package dev.openauth.app.vault

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.openauth.app.MainActivity
import dev.openauth.app.OpenAuthApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Настоящий системный prompt на одноразовом AVD с PIN, переданным через testPin. */
@RunWith(AndroidJUnit4::class)
class UnlockInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as OpenAuthApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var pin: String

    @Before fun prepareLockedVault() {
        pin = requireNotNull(InstrumentationRegistry.getArguments().getString("testPin")) {
            "Для проверки настоящего системного prompt требуется testPin"
        }
        require(pin.isNotEmpty()) { "testPin не должен быть пустым" }
        compose.runOnIdle { app.vault.lock() }
        // Самостоятельный запуск на чистой установке сначала создаёт защищённый контейнер.
        if (app.vault.state.value == VaultState.Uninitialized) {
            requestPin()
            enterPin()
            compose.runOnIdle { app.vault.lock() }
        }
        assertEquals(VaultState.Locked, app.vault.state.value)
        awaitUnlockAction()
    }

    @Test fun cancellingPromptKeepsVaultLockedAndFreshPinAttemptSucceeds() {
        requestPin()
        cancelPrompt()
        awaitUnlockAction()
        assertEquals("Отмена prompt не открывает сессию", VaultState.Locked, app.vault.state.value)

        requestPin()
        assertEquals("Недавняя системная авторизация не пропускает новый prompt", VaultState.Locked, app.vault.state.value)
        enterPin()
    }

    @Test fun homeDuringPromptKeepsVaultLockedAfterReturningToActivity() {
        requestPin()
        device.pressHome()
        awaitPromptGone()
        assertEquals("Уход на Home не открывает сессию", VaultState.Locked, app.vault.state.value)

        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        awaitUnlockAction()
        assertEquals("Возврат не использует прерванное подтверждение", VaultState.Locked, app.vault.state.value)
        requestPin()
        enterPin()
    }

    @Test fun recreationDuringPromptCannotLeaveAnOpenSessionOrBlockFreshAuthentication() {
        requestPin()
        // Пересоздание детерминированно покрывает замену Activity, происходящую при повороте.
        compose.activityRule.scenario.recreate()
        assertEquals(VaultState.Locked, app.vault.state.value)
        // Android может ещё удерживать prompt после пересоздания; его отмена обязана завершиться безопасно.
        if (device.findObject(PIN_INPUT) != null) cancelPrompt()
        awaitPromptGone()
        awaitUnlockAction()
        assertEquals("Старый prompt не открывает новую Activity", VaultState.Locked, app.vault.state.value)
        requestPin()
        enterPin()
    }

    private fun awaitUnlockAction() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("unlock-vault") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("unlock-vault").assertIsDisplayed().assertIsEnabled()
    }

    private fun requestPin() {
        awaitUnlockAction()
        compose.onNodeWithTag("unlock-vault").performClick()
        pinInput()
    }

    private fun pinInput(): UiObject2 {
        val input = device.wait(Until.findObject(PIN_INPUT), TIMEOUT_MS)
        assertNotNull("Ожидается настоящий системный ввод PIN", input)
        return requireNotNull(input)
    }

    private fun enterPin() {
        pinInput().text = pin
        device.pressEnter()
        compose.waitUntil(TIMEOUT_MS) { app.vault.state.value is VaultState.Unlocked }
        assertTrue(app.vault.state.value is VaultState.Unlocked)
    }

    private fun cancelPrompt() {
        device.pressBack()
        // На некоторых версиях первый Back закрывает только системную клавиатуру.
        if (!device.wait(Until.gone(PIN_INPUT), 1_000)) device.pressBack()
        awaitPromptGone()
    }

    private fun awaitPromptGone() {
        assertTrue("Системный prompt должен закрыться", device.wait(Until.gone(PIN_INPUT), TIMEOUT_MS))
        instrumentation.waitForIdleSync()
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private val PIN_INPUT = By.res("com.android.systemui", "lockPassword")
    }
}
