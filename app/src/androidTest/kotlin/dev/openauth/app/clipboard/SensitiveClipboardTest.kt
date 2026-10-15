package dev.openauth.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.openauth.app.MainActivity
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveClipboardTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun copyMarksSensitiveAndClearsOnlyItsOwnClip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var expiry: (() -> Unit)? = null
        var now = android.os.SystemClock.elapsedRealtime()
        val subject = SensitiveClipboard(context, nowMillis = { now }) { expiry = it }
        subject.copyCode("123456")
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        now += 30_001
        expiry!!()
        assertFalse(clipboard.hasPrimaryClip())
        subject.copyCode("654321")
        clipboard.setPrimaryClip(ClipData.newPlainText("Other application", "keep me"))
        now += 30_001
        expiry!!()
        assertEquals("keep me", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
    @Test fun staleTimerAndEarlyResumeDoNotClearNewerOwnedCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ClipboardManager::class.java)
        var now = android.os.SystemClock.elapsedRealtime()
        val callbacks = mutableListOf<() -> Unit>()
        val subject = SensitiveClipboard(context, nowMillis = { now }) { callbacks += it }
        subject.copyCode("123456")
        now += 25_000
        subject.copyCode("654321")
        now += 5_001
        callbacks.first()()
        subject.retryExpiredClip()
        assertEquals("654321", manager.primaryClip!!.getItemAt(0).text.toString())
        now += 25_000
        callbacks.last()()
        assertFalse(manager.hasPrimaryClip())
    }

}
