package dev.openauth.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.core.model.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val entry = OtpEntry(UUID.randomUUID(), "Example", "alice@example.org", OtpSecret.fromBytes(ByteArray(20) { 1 }),
        OtpType.TOTP, HashAlgorithm.SHA1, 6, 30, 0, null, false, 0)
    private val snapshot = VaultSnapshot(revision = 0, entries = listOf(entry), groups = emptyList())
    @Test fun codeCardHasAccessibleCopyFavoriteAndEditActions() {
        var copied: UUID? = null
        compose.setContent { OpenAuthTheme { AccountsScreen(snapshot = snapshot, codes = mapOf(entry.id to "123456"),
            epochSeconds = 59, onCopy = { copied = it }) } }
        compose.onNodeWithTag("code-${entry.id}").assertTextContains("123 456")
        compose.onNodeWithTag("copy-${entry.id}").performClick()
        compose.runOnIdle { assertEquals(entry.id, copied) }
        compose.onNodeWithTag("favorite-${entry.id}").assertHasClickAction()
        compose.onNodeWithTag("edit-${entry.id}").assertHasClickAction()
    }
    @Test fun emptyStateOffersManualAndScannerEntry() {
        compose.setContent { OpenAuthTheme { AccountsScreen(VaultSnapshot(revision = 0, entries = emptyList(), groups = emptyList())) } }
        compose.onNodeWithTag("add-account").assertHasClickAction()
        compose.onNodeWithTag("scan-account").assertHasClickAction()
    }
    @Test fun largeFontActionsAreFullWidthAndRemainReachable() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                OpenAuthTheme { AccountsScreen(snapshot) }
            }
        }
        val scan = compose.onNodeWithTag("scan-account").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("add-account").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(scan.left, add.left, 1f)
        assertTrue("Actions stack without narrow columns at 200%", add.top >= scan.bottom)
        compose.onNodeWithTag("add-account").assertHasClickAction()
    }
    @Test fun cameraDeniedOffersManualEntryWithoutCamera() {
        var manual = false
        compose.setContent { OpenAuthTheme { dev.openauth.app.scanner.CameraUnavailable(onManual = { manual = true }, onCancel = {}) } }
        compose.onNodeWithTag("camera-manual").performClick()
        compose.runOnIdle { assertTrue(manual) }
    }
}
