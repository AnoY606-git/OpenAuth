package dev.openauth.app.integration

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openauth.app.MainActivity
import dev.openauth.app.OpenAuthApplication
import dev.openauth.app.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lockedProductionActivityExposesRealFileBackupAndCloudControls() {
        val app = compose.activity.application as OpenAuthApplication
        compose.runOnIdle {
            app.vault.lock()
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onAllNodesWithText(compose.activity.getString(R.string.backups)).onLast().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.backup_export)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.backup_open)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.drive_connect)).performScrollTo().assertIsDisplayed()
    }
}
