package dev.openauth.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Реальная потеря input focus через Home; scheduler/монотонное время управляемы без30с ожидания. */
@RunWith(AndroidJUnit4::class)
class ClipboardForegroundExpiryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expiredOwnedClipIsRetriedAfterHomeAndReturn() = exercise(replaceClip = false)
    @Test fun expiredCleanupKeepsNewerForeignClipAfterHomeAndReturn() = exercise(replaceClip = true)

    private fun exercise(replaceClip: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(ClipboardManager::class.java)
        var now = SystemClock.elapsedRealtime()
        var expire: (() -> Unit)? = null
        val subject = SensitiveClipboard(context, nowMillis = { now }) { expire = it }
        compose.setContent { ClipboardExpiryEffect(subject) }
        compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
        compose.runOnUiThread {
            subject.copyCode("123456")
            if (replaceClip) manager.setPrimaryClip(ClipData.newPlainText("Other application", "keep this newer clip"))
        }
        val device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        compose.waitUntil(5000) { !compose.activity.hasWindowFocus() }
        // Настоящий Android ClipboardManager в фоне запрещает прочитать metadata.
        compose.runOnUiThread {
            assertNull(manager.primaryClipDescription)
            now += 30_001
            expire!!()
        }
        context.startActivity(Intent(context, ComponentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
        compose.runOnUiThread {
            if (replaceClip) assertEquals("keep this newer clip", manager.primaryClip!!.getItemAt(0).text.toString())
            else assertFalse("Просроченный собственный clip должен очиститься при возврате фокуса", manager.hasPrimaryClip())
        }
    }
}
