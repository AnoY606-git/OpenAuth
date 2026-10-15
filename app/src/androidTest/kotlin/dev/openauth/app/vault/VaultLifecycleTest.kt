package dev.openauth.app.vault

import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.openauth.app.MainActivity
import dev.openauth.app.OpenAuthApplication
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultLifecycleTest {
    @Test fun realCredentialUnlockRotationBackgroundAndScreenPrivacy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pin = InstrumentationRegistry.getArguments().getString("testPin")
        assumeNotNull(pin)
        val app = instrumentation.targetContext.applicationContext as OpenAuthApplication
        app.vault.lock()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val device = UiDevice.getInstance(instrumentation)
            val action = device.wait(Until.findObject(By.text(Pattern.compile("Создать хранилище|Разблокировать|Create vault|Unlock"))), 10_000)
            assertNotNull("Экран должен предлагать системную аутентификацию", action)
            action.click()
            val input = device.wait(Until.findObject(By.res("com.android.systemui", "lockPassword")), 10_000)
            assertNotNull("Ожидается настоящее системное окно PIN", input)
            input.text = pin!!
            device.pressEnter()
            val deadline = System.currentTimeMillis() + 10_000
            while (app.vault.state.value !is VaultState.Unlocked && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(app.vault.state.value is VaultState.Unlocked)
            scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            scenario.recreate()
            assertTrue("Поворот не закрывает сессию", app.vault.state.value is VaultState.Unlocked)
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(VaultState.Locked, app.vault.state.value)
        }
    }
}
