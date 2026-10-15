package dev.openauth.app.integration

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
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
import dev.openauth.app.ui.AccountSort
import dev.openauth.app.ui.selectAccounts
import dev.openauth.core.model.*
import dev.openauth.core.otp.Base32
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Только androidTest: production всегда сохраняет FLAG_SECURE и настоящую аутентификацию. */
@RunWith(AndroidJUnit4::class)
class VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as OpenAuthApplication
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun captureSyntheticCodesAndBackupsInBothThemes() {
        val arguments = InstrumentationRegistry.getArguments()
        val pin = arguments.getString("testPin")
        assumeNotNull(pin)
        compose.onNodeWithTag("unlock-vault").performClick()
        val input = device.wait(Until.findObject(By.res("com.android.systemui", "lockPassword")), 10_000)
        assertNotNull(input); input.text = pin!!; device.pressEnter()
        compose.waitUntil(10_000) { app.vault.state.value is VaultState.Unlocked }
        val requestedCount = arguments.getString("syntheticCount")
        if (requestedCount != null) {
            val count = requestedCount.toInt()
            require(count == 500 || count == 1000) { "syntheticCount must be 500 or 1000" }
            measureProductionList(count)
            return
        }
        val model = OpenAuthViewModel(app.vault)
        runBlocking {
            for (issuer in listOf("GitHub", "Google", "Microsoft", "Example mail")) {
                val existing = (app.vault.state.value as VaultState.Unlocked).snapshot.entries.singleOrNull { it.accountName == "synthetic@example.test" && it.issuer == issuer }
                model.save(AccountDraft(id = existing?.id, issuer = issuer, accountName = "synthetic@example.test",
                    secret = Base32.encode(OtpSecret.fromBytes("OpenAuth demo $issuer".toByteArray(Charsets.UTF_8)))))
            }
        }
        val preferences = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val previous = preferences.getString("theme", null)
        val label = (arguments.getString("captureLabel") ?: "default").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        try {
            for (theme in listOf("LIGHT", "DARK")) {
                preferences.edit().putString("theme", theme).commit()
                compose.activityRule.scenario.recreate()
                compose.waitForIdle()
                compose.onNodeWithTag("account-search").performTextReplacement("synthetic@example.test")
                device.pressBack()
                compose.waitForIdle()
                capture("codes-${theme.lowercase()}-$label.png")
                if (theme == "LIGHT") {
                    compose.onAllNodesWithText(compose.activity.getString(R.string.backups)).onLast().performClick()
                    compose.onNodeWithText(compose.activity.getString(R.string.backup_title)).assertIsDisplayed()
                    capture("backups-$label.png")
                    compose.onNodeWithText(compose.activity.getString(R.string.drive_in_development)).performScrollTo()
                    capture("backups-drive-$label.png")
                }
            }
        } finally {
            preferences.edit().apply { if (previous == null) remove("theme") else putString("theme", previous) }.commit()
        }
    }

    /** Измеряет production Activity, реальный OTP и прокрутку; результаты не задают универсального порога. */
    private fun measureProductionList(count: Int) {
        val prefix = "OpenAuth benchmark $count / "
        val synthetic = List(count) { index ->
            val identity = "openauth/instrumentation/benchmark/$count/$index"
            OtpEntry(
                id = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)),
                issuer = prefix + index.toString().padStart(4, '0'),
                accountName = "synthetic-$index@example.test",
                secret = OtpSecret.fromBytes(ByteArray(20) { offset -> ((index * 17 + offset * 13) and 255).toByte() }),
                type = OtpType.TOTP, algorithm = HashAlgorithm.SHA1, digits = 6,
                periodSeconds = 30, nextCounter = 0, groupId = null, favorite = false,
                sortIndex = index.toLong(),
            )
        }
        val seedStarted = SystemClock.elapsedRealtimeNanos()
        runBlocking {
            app.vault.mutate { current ->
                val ids = current.entries.mapTo(HashSet()) { it.id }
                // Повторный запуск идемпотентен. Существующие записи не удаляются и не переписываются.
                VaultSnapshot(revision = current.revision, entries = current.entries + synthetic.filterNot { it.id in ids }, groups = current.groups)
            }
        }
        val seedElapsed = SystemClock.elapsedRealtimeNanos() - seedStarted
        val snapshot = (app.vault.state.value as VaultState.Unlocked).snapshot
        assertEquals(count, selectAccounts(snapshot, prefix, false, null, AccountSort.ISSUER).size)
        val renderStarted = SystemClock.elapsedRealtimeNanos()
        compose.onNodeWithTag("account-search").performTextReplacement(prefix)
        device.pressBack()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("code-${synthetic.first().id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("code-${synthetic.first().id}").fetchSemanticsNode()
                .config[SemanticsProperties.Text].any { it.text.matches(Regex("[0-9]{3} [0-9]{3}")) }
        }
        compose.waitForIdle()
        val firstCodeElapsed = SystemClock.elapsedRealtimeNanos() - renderStarted
        val directory = File(app.getExternalFilesDir(null), "performance").apply { mkdirs() }
        val stem = "production-$count-${System.currentTimeMillis()}"
        val packageName = app.packageName
        File(directory, "$stem-meminfo-before.txt").writeText(device.executeShellCommand("dumpsys meminfo $packageName"))
        val before = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        File(directory, "$stem-gfxinfo-reset.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $packageName reset"))
        val bounds = compose.onNodeWithTag("accounts-list").fetchSemanticsNode().boundsInRoot
        val x = bounds.center.x.toInt()
        val top = (bounds.top + bounds.height * 0.2f).toInt()
        val bottom = (bounds.top + bounds.height * 0.8f).toInt()
        val swipeStarted = SystemClock.elapsedRealtimeNanos()
        repeat(10) {
            assertTrue("UiDevice swipe failed", device.swipe(x, bottom, x, top, 30))
        }
        compose.waitForIdle(); device.waitForIdle()
        val swipeElapsed = SystemClock.elapsedRealtimeNanos() - swipeStarted
        val after = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val nativeAfter = Debug.getNativeHeapAllocatedSize()
        File(directory, "$stem-gfxinfo-framestats.txt").writeText(device.executeShellCommand("dumpsys gfxinfo $packageName framestats"))
        File(directory, "$stem-meminfo-after.txt").writeText(device.executeShellCommand("dumpsys meminfo $packageName"))
        val summary = "synthetic_count=$count vault_count=${snapshot.entries.size} swipes=10 " +
            "seed_ns=$seedElapsed first_code_ns=$firstCodeElapsed swipe_ns=$swipeElapsed " +
            "pss_before_kb=${before.totalPss} pss_after_kb=${after.totalPss} " +
            "native_before_bytes=$nativeBefore native_after_bytes=$nativeAfter " +
            "font_scale=${app.resources.configuration.fontScale} sdk=${android.os.Build.VERSION.SDK_INT}"
        File(directory, "$stem-summary.txt").writeText(summary + "\n")
        Log.i("OpenAuthPerformance", summary)
    }

    private fun capture(name: String) {
        val target = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            compose.waitForIdle(); device.waitForIdle()
            assertTrue("Could not save screenshot", device.takeScreenshot(File(target, name)))
        } finally {
            compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
