package dev.openauth.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openauth.app.R
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.core.model.VaultSnapshot
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.restore.RestorePlan
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestorePreviewLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val plan = RestorePlan(VaultSnapshot(revision = 0, entries = emptyList(), groups = emptyList()),
        added = 0, duplicates = 0, remappedIds = 0, hotpWarnings = emptyList())

    @Test fun tappingReplaceLabelEnablesRestoreAndSecondTapDisablesIt() {
        var confirmed = 0
        compose.setContent { OpenAuthTheme {
            RestorePreviewScreen(plan, RestoreMode.REPLACE, busy = false, onConfirm = { confirmed++ }, onCancel = {})
        } }
        val confirm = compose.onNodeWithText(context.getString(R.string.backup_restore_confirm))
        val label = compose.onNodeWithText(context.getString(R.string.backup_replace_confirm), useUnmergedTree = true)
        confirm.assertIsNotEnabled()
        label.performScrollTo().performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        confirm.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
        label.performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOff()
        confirm.assertIsNotEnabled()
    }

    @Test fun busyRestoreIgnoresTouchesOnConfirmationLabel() {
        val busy = mutableStateOf(false)
        compose.setContent { OpenAuthTheme {
            RestorePreviewScreen(plan, RestoreMode.REPLACE, busy.value, onConfirm = {}, onCancel = {})
        } }
        val label = compose.onNodeWithText(context.getString(R.string.backup_replace_confirm), useUnmergedTree = true)
        label.performScrollTo().performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { busy.value = true }
        compose.onNode(isToggleable()).assertIsNotEnabled()
        label.performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.backup_restore_confirm)).assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        label.performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOff()
        compose.runOnIdle { busy.value = true }
        label.performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun confirmationLabelStaysReadableAndOperableAtNarrowWidthsAndLargeFonts() {
        val contexts = listOf("ru", "en").associateWith { language ->
            context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
        }
        val configuration = mutableStateOf(DialogConfiguration("ru", 320, 1f))
        compose.setContent {
            val current = configuration.value
            val localized = contexts.getValue(current.language)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Dialog создаёт своё окно: меняем его логическую ширину через унаследованный Density.
                val density = LocalDensity.current.density * maxWidth.value / current.width
                CompositionLocalProvider(LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalResources provides localized.resources,
                    LocalDensity provides Density(density, current.fontScale)) {
                    key(current) { OpenAuthTheme {
                        RestorePreviewScreen(plan, RestoreMode.REPLACE, busy = false, onConfirm = {}, onCancel = {})
                    } }
                }
            }
        }
        for (language in contexts.keys) for (width in listOf(320, 360, 412)) for (fontScale in listOf(1f, 1.3f, 2f)) {
            val current = DialogConfiguration(language, width, fontScale)
            compose.runOnIdle { configuration.value = current }
            compose.onNodeWithText(contexts.getValue(language).getString(R.string.backup_preview_title)).assertExists()
            val text = contexts.getValue(language).getString(R.string.backup_replace_confirm)
            val label = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val visible = label.fetchSemanticsNode().boundsInRoot
            for (line in 0 until layout.lineCount) {
                assertTrue("Confirmation clips horizontally at $current",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertFalse("Confirmation is ellipsized at $current", layout.isLineEllipsized(line))
            }
            assertEquals("Complete confirmation text at $current", text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue("Confirmation clips vertically at $current", layout.size.height <= visible.height + 1f)
            assertTrue("Confirmation clips outside dialog at $current", layout.size.width <= visible.width + 1f)
            label.performTouchInput { click() }
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText(contexts.getValue(language).getString(R.string.backup_restore_confirm)).assertIsEnabled()
        }
    }

    private data class DialogConfiguration(val language: String, val width: Int, val fontScale: Float)
}
