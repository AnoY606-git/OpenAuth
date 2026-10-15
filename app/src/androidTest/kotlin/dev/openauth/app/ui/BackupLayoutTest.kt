package dev.openauth.app.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openauth.app.R
import dev.openauth.app.backup.BackupCoordinator
import dev.openauth.app.backup.EncryptedSnapshotQueue
import dev.openauth.app.backup.MetadataProtector
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.app.vault.VaultKeyProtector
import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultStorage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Проверяет настоящие callbacks и геометрию BackupScreen; внешние ключи заменены только на границе. */
@RunWith(AndroidJUnit4::class)
class BackupLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = VaultRepository(object : VaultStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }, object : VaultKeyProtector {
        override fun prepare(create: Boolean) = Unit
        override fun wrap(key: ByteArray) = key.copyOf()
        override fun unwrap(wrapped: ByteArray) = wrapped.copyOf()
    }, Dispatchers.Unconfined)
    private lateinit var coordinator: BackupCoordinator

    @Before fun prepare() {
        runBlocking { repository.initialize() }
        coordinator = BackupCoordinator(repository, EncryptedSnapshotQueue(temporary.newFolder(), object : MetadataProtector {
            override fun seal(bytes: ByteArray) = bytes.copyOf()
            override fun open(bytes: ByteArray) = bytes.copyOf()
        }), dispatcher = Dispatchers.Unconfined, automaticSnapshotsEnabled = false)
    }

    @Test fun backupActionsStayReadableAndMatchAtOrdinaryFontAcrossLocalesAndWidths() {
        val contexts = listOf("ru", "en").associateWith { language ->
            context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
        }
        val language = mutableStateOf("ru")
        val size = mutableStateOf(Window(320, 568, 1f))
        compose.setContent {
            val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            val localized = contexts.getValue(language.value)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalResources provides localized.resources,
            ) {
                TestWindow(size.value) { BackupScreen(coordinator, repository, onUnlock = {}) }
            }
        }
        for (locale in contexts.keys) for ((width, height) in listOf(320 to 568, 360 to 640, 412 to 732, 640 to 200, 600 to 800)) {
            for (font in listOf(1f, 1.3f, 2f)) {
                val window = Window(width, height, font)
                val scenario = "$locale $window"
                compose.runOnIdle { language.value = locale; size.value = window }
                val heights = mutableListOf<Float>()
                val widths = mutableListOf<Float>()
                for (resource in listOf(R.string.backup_configure, R.string.backup_export, R.string.backup_open)) {
                    val label = contexts.getValue(locale).getString(resource)
                    val button = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
                    val text = compose.onNodeWithText(label, useUnmergedTree = true)
                    val layouts = mutableListOf<TextLayoutResult>()
                    text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    val bounds = button.fetchSemanticsNode().boundsInRoot
                    val visible = text.fetchSemanticsNode().boundsInRoot
                    for (line in 0 until layout.lineCount) {
                        assertTrue("$label clips horizontally at $scenario", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                        assertFalse("$label must not be ellipsized at $scenario", layout.isLineEllipsized(line))
                    }
                    assertEquals("Complete backup action at $scenario", label.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("$label clips vertically at $scenario", layout.size.height <= visible.height + 1f)
                    assertTrue("$label clips outside its container at $scenario", layout.size.width <= visible.width + 1f)
                    assertTrue("Minimum action height at $scenario", bounds.height >= 48f * layout.layoutInput.density.density - 1f)
                    heights += bounds.height
                    widths += bounds.width
                }
                assertTrue("Action widths must match at $scenario", widths.max() - widths.min() <= 1f)
                if (font == 1f) assertTrue("Ordinary-font backup action heights must match at $scenario: $heights", heights.max() - heights.min() <= 1f)
            }
        }
    }

    @Test fun tappingPinLabelChangesCredentialModeAndClearsEnteredPassword() {
        compose.setContent { OpenAuthTheme { BackupScreen(coordinator, repository, onUnlock = {}) } }
        compose.onNodeWithText(context.getString(R.string.backup_configure)).performScrollTo().performClick()
        compose.onNode(hasText(context.getString(R.string.backup_password_label)) and hasSetTextAction())
            .performTextInput("Synthetic password")
        val label = context.getString(R.string.backup_use_pin)
        compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.backup_pin_label)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(label, useUnmergedTree = true).performTouchInput { click() }
        compose.onNode(hasText(context.getString(R.string.backup_password_label)) and hasSetTextAction()).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun tappingRestoreModeLabelsChangesTheSelectedMode() {
        // Диалог выбора не читает URI до подтверждения; доступ к DocumentsUI здесь не нужен.
        coordinator.files.stageImport(Uri.parse("content://dev.openauth.test/synthetic-backup"))
        compose.setContent { OpenAuthTheme { BackupScreen(coordinator, repository, onUnlock = {}) } }
        compose.onNodeWithText(context.getString(R.string.backup_continue_restore)).performScrollTo().performClick()
        compose.onAllNodes(isSelectable()).onFirst().assertIsSelected()
        compose.onAllNodes(isSelectable()).onLast().assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.backup_replace), useUnmergedTree = true)
            .performScrollTo().performTouchInput { click() }
        compose.onAllNodes(isSelectable()).onLast().assertIsSelected()
        compose.onAllNodes(isSelectable()).onFirst().assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.backup_merge), useUnmergedTree = true)
            .performScrollTo().performTouchInput { click() }
        compose.onAllNodes(isSelectable()).onFirst().assertIsSelected()
        compose.onAllNodes(isSelectable()).onLast().assertIsNotSelected()
    }

    private data class Window(val width: Int, val height: Int, val fontScale: Float)

    @Composable
    private fun TestWindow(window: Window, content: @Composable () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
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
