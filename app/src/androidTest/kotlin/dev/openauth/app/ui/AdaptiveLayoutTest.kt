package dev.openauth.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openauth.app.R
import dev.openauth.app.scanner.CameraUnavailable
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.core.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяет реальные Compose-границы, перенос текста и доступность действий в малом окне. */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val entry = OtpEntry(
        UUID.randomUUID(), "Example", "alice@example.test", OtpSecret.fromBytes(ByteArray(20) { 1 }),
        OtpType.TOTP, HashAlgorithm.SHA1, 8, 30, 0, null, false, 0,
    )
    private val snapshot = VaultSnapshot(revision = 0, entries = listOf(entry), groups = emptyList())

    @Test fun eightDigitCodesStayWholeAndSingleLineAcrossWindowAndFontSizes() {
        val size = mutableStateOf(Window(320, 568, 2f))
        compose.setContent {
            TestWindow(size.value) {
                AccountsScreen(snapshot, codes = mapOf(entry.id to "12345678"))
            }
        }
        for (window in windows) {
            compose.runOnIdle { size.value = window }
            val list = compose.onNodeWithTag("accounts-list")
            assertTrue("List must have visible height before scrolling at $window", list.fetchSemanticsNode().boundsInRoot.height > 0f)
            list.performScrollToNode(hasTestTag("code-${entry.id}"))
            val code = compose.onNodeWithTag("code-${entry.id}").performScrollTo().assertIsDisplayed()
            code.assertTextContains("1234 5678")
            val layouts = mutableListOf<TextLayoutResult>()
            code.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            android.util.Log.i("OpenAuthLayoutTest", "$window textSize=${layouts.single().size} fontScale=${layouts.single().layoutInput.density.fontScale}")
            assertEquals("OTP must stay on one line at $window", 1, layouts.single().lineCount)
            assertTextFits("OTP at $window", code, layouts.single())
            compose.onNodeWithTag("copy-${entry.id}").assertHasClickAction()
        }
    }

    @Test fun accountListRemainsScrollableWhenLandscapeBarsAndLargeFontLeaveLittleHeight() {
        compose.setContent { TestWindow(Window(640, 200, 2f)) { AccountsScreen(snapshot) } }
        val list = compose.onNodeWithTag("accounts-list")
        assertTrue("List must have visible height before scrolling", list.fetchSemanticsNode().boundsInRoot.height > 0f)
        list.performScrollToNode(hasTestTag("edit-${entry.id}"))
        compose.onNodeWithTag("edit-${entry.id}").assertIsDisplayed()
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("add-account"))
        compose.onNodeWithTag("add-account").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun cameraFallbackActionsCanBeReachedInShortWindowWithLargeText() {
        var manual = false
        var cancelled = false
        compose.setContent {
            TestWindow(Window(320, 240, 2f)) {
                CameraUnavailable(onManual = { manual = true }, onCancel = { cancelled = true })
            }
        }
        compose.onNodeWithTag("camera-manual").performScrollTo().assertIsDisplayed().performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.cancel)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(manual); assertTrue(cancelled) }
    }

    @Test fun editorAlgorithmsWrapInsteadOfClippingAtLargeFont() {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current.density * maxWidth.value / 320f
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    OpenAuthTheme { AccountEditor(AccountDraft(), emptyList(), false, null, {}, {}) }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.advanced)).performScrollTo().performClick()
        for (label in listOf("SHA-1", "SHA-256", "SHA-512")) {
            val algorithm = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            algorithm.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Algorithm must remain readable on one line: $label", 1, layouts.single().lineCount)
            assertTextFits("Algorithm $label", algorithm, layouts.single())
            algorithm.performClick()
        }
        compose.onNodeWithTag("editor-save").performScrollTo().assertIsDisplayed()
    }

    private fun assertTextFits(label: String, node: SemanticsNodeInteraction, layout: TextLayoutResult) {
        // MultiParagraph.width — ширина ограничения, а не глифов; didOverflowWidth даёт false positive
        // для wrap-content Text в Compose 1.9. Проверяем строки и реально видимую область узла.
        val visible = node.fetchSemanticsNode().boundsInRoot
        for (line in 0 until layout.lineCount) {
            assertTrue("$label clips horizontally: line=${layout.getLineLeft(line)}..${layout.getLineRight(line)}, size=${layout.size}, visible=$visible",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        assertTrue("$label clips vertically: size=${layout.size}, visible=$visible", layout.size.height <= visible.height + 1f)
        assertTrue("$label clips outside its container: size=${layout.size}, visible=$visible", layout.size.width <= visible.width + 1f)
    }

    private data class Window(val width: Int, val height: Int, val fontScale: Float)
    private val windows = listOf(320 to 568, 360 to 640, 412 to 732, 640 to 200, 600 to 800)
        .flatMap { (width, height) -> listOf(1f, 1.3f, 2f).map { Window(width, height, it) } }

    @Composable
    private fun TestWindow(window: Window, content: @Composable () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Умещает геометрию тестового окна на устройстве без изменения системных settings.
            val physicalDensity = LocalDensity.current.density
            val density = minOf(maxWidth.value * physicalDensity / window.width, maxHeight.value * physicalDensity / window.height)
            CompositionLocalProvider(LocalDensity provides Density(density, window.fontScale)) {
                key(window) {
                    Box(Modifier.requiredSize(window.width.dp, window.height.dp)) { OpenAuthTheme { content() } }
                }
            }
        }
    }
}
