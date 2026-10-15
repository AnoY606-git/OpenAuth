package dev.openauth.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openauth.app.R
import dev.openauth.app.vault.VaultKeyProtector
import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultStorage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppChromeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleAndDestinationSubtitleFitAtLargeFontAndEveryNavigationItemRemainsUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contexts = listOf("ru", "en").associateWith { language ->
            context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
        }
        val repository = VaultRepository(object : VaultStorage {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }, object : VaultKeyProtector {
            override fun prepare(create: Boolean) = Unit
            override fun wrap(key: ByteArray) = key.copyOf()
            override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
        }, Dispatchers.Unconfined)
        runBlocking { repository.initialize() }
        val model = OpenAuthViewModel(repository)
        val scenario = mutableStateOf(Window("ru", 320, 568))
        compose.setContent {
            val window = scenario.value
            val localized = contexts.getValue(window.language)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val physicalDensity = LocalDensity.current.density
                val density = minOf(maxWidth.value * physicalDensity / window.width, maxHeight.value * physicalDensity / window.height)
                CompositionLocalProvider(LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalResources provides localized.resources,
                    LocalDensity provides Density(density, 2f)) {
                    key(window) {
                        Box(Modifier.requiredSize(window.width.dp, window.height.dp)) {
                            OpenAuthApp(model, onUnlock = {}, backupContent = {}, clock = OtpClock { 59 })
                        }
                    }
                }
            }
        }
        for (language in contexts.keys) for ((width, height) in listOf(320 to 568, 360 to 640, 412 to 732, 640 to 200, 600 to 800)) {
            val current = Window(language, width, height)
            compose.runOnIdle { scenario.value = current }
            for (resource in listOf(R.string.codes, R.string.backups, R.string.settings)) {
                val label = contexts.getValue(language).getString(resource)
                val navigation = compose.onNode(hasText(label) and isSelectable())
                navigation.assertIsDisplayed().performClick().assertIsSelected()
                assertCompleteText(compose.onNodeWithText("Open Auth"), "Open Auth", current)
                val subtitle = compose.onNode(hasText(label) and !hasAnyAncestor(isSelectable()), useUnmergedTree = true)
                assertCompleteText(subtitle, label, current)
            }
        }
    }

    private fun assertCompleteText(node: SemanticsNodeInteraction, text: String, window: Window) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visible = node.fetchSemanticsNode().boundsInRoot
        for (line in 0 until layout.lineCount) {
            assertTrue("$text clips horizontally at $window: size=${layout.size}, visible=$visible",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            assertFalse("$text is ellipsized at $window", layout.isLineEllipsized(line))
        }
        assertEquals("Complete $text at $window", text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("$text clips vertically at $window: size=${layout.size}, visible=$visible", layout.size.height <= visible.height + 1f)
        assertTrue("$text clips outside app bar at $window", layout.size.width <= visible.width + 1f)
    }

    private data class Window(val language: String, val width: Int, val height: Int)
}
