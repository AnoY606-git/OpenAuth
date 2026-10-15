package dev.openauth.app.performance

import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openauth.app.ui.*
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.core.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Синтетические данные без секретов пользователя. Метрики дополнительно снимаются root через adb. */
@RunWith(AndroidJUnit4::class)
class AccountListPerformanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun list500() = measure(500)
    @Test fun list1000() = measure(1000)
    private fun measure(count: Int) {
        val entries = List(count) { index -> OtpEntry(UUID.randomUUID(), "Service $index", "synthetic", OtpSecret.fromBytes(ByteArray(20) { 1 }),
            OtpType.TOTP, HashAlgorithm.SHA1, 6, 30, 0, null, false, index.toLong()) }
        val calls = AtomicInteger()
        val generator = VisibleCodeGenerator { _, _ -> calls.incrementAndGet(); "123456" }
        val start = SystemClock.elapsedRealtimeNanos()
        compose.setContent { OpenAuthTheme { AccountsScreen(VaultSnapshot(revision = 0, entries = entries, groups = emptyList()),
            onVisibleIds = { generator.generate(entries, it, 59) }) } }
        compose.waitForIdle()
        val initial = calls.get()
        assertTrue("Must generate only viewport accounts, was $initial", initial in 1..30)
        compose.onNodeWithTag("accounts-list").performScrollToIndex(count - 1)
        compose.waitForIdle()
        assertTrue(calls.get() < count)
        android.util.Log.i("OpenAuthPerformance", "synthetic_count=$count elapsed_ns=${SystemClock.elapsedRealtimeNanos()-start} generated=${calls.get()} native_heap=${Debug.getNativeHeapAllocatedSize()}")
    }
}
