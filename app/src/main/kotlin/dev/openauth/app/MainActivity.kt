package dev.openauth.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import dev.openauth.app.ui.BackupScreen
import dev.openauth.app.ui.DriveBackupSettings
import dev.openauth.app.ui.OpenAuthApp
import dev.openauth.app.ui.OpenAuthViewModel
import dev.openauth.app.vault.UnlockCoordinator
import dev.openauth.app.vault.VaultState

class MainActivity : FragmentActivity() {
    private val app get() = application as OpenAuthApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        val unlock = UnlockCoordinator(this, app.vault, app.deviceKeys)
        setContent {
            val viewModel = remember(app.vault) { OpenAuthViewModel(app.vault) }
            val unlockForBackup: suspend () -> Unit = {
                if (app.vault.state.value !is VaultState.Unlocked) {
                    unlock.unlock(initialize = app.vault.state.value == VaultState.Uninitialized)
                }
            }
            OpenAuthApp(
                viewModel = viewModel,
                onUnlock = { initialize -> unlock.unlock(initialize) },
                backupContent = {
                    BackupScreen(app.backup, app.vault, unlockForBackup, cloudContent = {
                        DriveBackupSettings(app.driveConnection, app.driveAuthorization, app.driveRestoreBrowser,
                            onUnlock = unlockForBackup,
                            onRestoreCiphertext = app.backup.files::stageCiphertext)
                    })
                },
            )
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) app.vault.lock()
        super.onStop()
    }
}
