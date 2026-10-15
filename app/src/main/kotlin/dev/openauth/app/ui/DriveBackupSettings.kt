package dev.openauth.app.ui

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openauth.app.R
import dev.openauth.app.drive.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Облако оперирует ciphertext; пароль, preview и применение остаются в BackupScreen. */
@Composable
fun DriveBackupSettings(
    controller: DriveConnectionController,
    authorization: DriveAuthorization,
    browser: DriveRestoreBrowser,
    onUnlock: suspend () -> Unit,
    onRestoreCiphertext: (ByteArray) -> Unit,
) {
    if (!DriveFeature.enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider()
            Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.drive_in_development), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.drive_development_file_backup), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val sync by controller.status.state.collectAsState()
    var destination by remember { mutableStateOf<DriveDestination?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<DriveHistory?>(null) }
    var confirmRevoke by remember { mutableStateOf<DriveDestination?>(null) }
    var confirmDelete by remember { mutableStateOf<DriveDestination?>(null) }
    var accountRequest by remember { mutableStateOf<String?>(null) }
    var newConnection by remember { mutableStateOf(true) }
    var authAttempt by remember { mutableIntStateOf(0) }
    var consentAttempted by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf<PendingIntent?>(null) }
    var waitingForSystem by remember { mutableStateOf(false) }
    val enabled = !busy && !waitingForSystem

    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DriveException) { message = if (failure.failure == DriveFailure.AUTH_REQUIRED) R.string.drive_auth_required else R.string.drive_operation_failed }
            catch (failure: DriveConnectionException) { message = if (failure.failure == DriveConnectionFailure.UNAVAILABLE) R.string.drive_unavailable else R.string.drive_operation_failed }
            catch (_: Exception) { message = R.string.drive_operation_failed }
            finally {
                try { destination = controller.destination() } catch (_: Exception) { destination = null }
                busy = false
            }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        waitingForSystem = false
        if (result.resultCode == Activity.RESULT_OK && accountRequest != null) authAttempt++
        else { accountRequest = null; message = R.string.drive_consent_cancelled }
    }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waitingForSystem = false
        val account = if (result.resultCode == Activity.RESULT_OK) authorization.selectedAccount(result.data) else null
        if (account == null) { message = R.string.drive_consent_cancelled }
        else { accountRequest = account; newConnection = true; consentAttempted = false; authAttempt++ }
    }
    LaunchedEffect(authAttempt) {
        val account = accountRequest ?: return@LaunchedEffect
        execute {
            when (val access = authorization.authorize(account)) {
                is DriveAccess.Granted -> {
                    if (access.accountName != account) throw DriveException(DriveFailure.AUTH_REQUIRED)
                    if (newConnection) controller.connect(account) else controller.resumeUploads()
                    accountRequest = null
                }
                is DriveAccess.NeedsConsent -> {
                    if (consentAttempted || access.resolution == null) {
                        accountRequest = null; message = R.string.drive_auth_required
                    } else { consentAttempted = true; resolution = access.resolution }
                }
                DriveAccess.Unavailable -> { accountRequest = null; message = R.string.drive_unavailable }
                DriveAccess.Denied -> { accountRequest = null; message = R.string.drive_setup_error }
                is DriveAccess.Failure -> {
                    accountRequest = null
                    message = when (access.reason) {
                        DriveOAuthFailure.RETRYABLE -> R.string.drive_operation_failed
                        DriveOAuthFailure.CANCELLED -> R.string.drive_consent_cancelled
                        DriveOAuthFailure.CONFIGURATION -> R.string.drive_setup_error
                        DriveOAuthFailure.DENIED -> R.string.drive_auth_required
                    }
                }
            }
        }
    }
    LaunchedEffect(resolution) {
        val pending = resolution ?: return@LaunchedEffect
        resolution = null; waitingForSystem = true
        try { consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
        catch (_: Exception) { waitingForSystem = false; accountRequest = null; message = R.string.drive_operation_failed }
    }
    LaunchedEffect(sync.configurationId) {
        try { controller.refreshStatus(); destination = controller.destination() }
        catch (_: Exception) { destination = null; message = R.string.drive_operation_failed }
        if (history?.destination != destination) history = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.drive_description), style = MaterialTheme.typography.bodyMedium)
        destination?.let { Text(it.accountName, style = MaterialTheme.typography.labelLarge) }
        val phase = if (destination == null) DriveSyncPhase.DISCONNECTED
            else if (sync.configurationId == destination?.configurationId) sync.phase else DriveSyncPhase.WAITING_FOR_UNLOCK
        Text(stringResource(phase.label()))
        if (destination != null) {
            val confirmed = sync.takeIf { it.configurationId == destination?.configurationId }?.lastUploadedAtEpochSeconds
            Text(confirmed?.let { snapshotDate(it, locale) }?.let { stringResource(R.string.drive_last_confirmed, it) }
                ?: stringResource(R.string.drive_never_confirmed), style = MaterialTheme.typography.bodyMedium)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
            if (!authorization.available()) message = R.string.drive_unavailable
            else try { waitingForSystem = true; accountPicker.launch(authorization.accountPicker()) }
            catch (_: Exception) { waitingForSystem = false; message = R.string.drive_unavailable }
        }) { Text(stringResource(if (destination == null) R.string.drive_connect else R.string.drive_change_account)) }
        if (destination != null) {
            if (sync.phase == DriveSyncPhase.NEEDS_CONSENT) {
                OutlinedButton(enabled = enabled, onClick = {
                    accountRequest = destination?.accountName; newConnection = false; consentAttempted = false; authAttempt++
                }) { Text(stringResource(R.string.drive_authorize_again)) }
            }
            OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = { execute {
                onUnlock(); controller.uploadNow()
            } }) { Text(stringResource(R.string.drive_upload_now)) }
            OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = { execute { history = browser.list() } }) {
                Text(stringResource(R.string.drive_restore))
            }
            TextButton(enabled = enabled, onClick = { execute { controller.disconnect(); history = null } }) {
                Text(stringResource(R.string.drive_disconnect))
            }
            TextButton(enabled = enabled, onClick = { confirmRevoke = destination }) {
                Text(stringResource(R.string.drive_revoke))
            }
            TextButton(enabled = enabled, onClick = { confirmDelete = destination }) {
                Text(stringResource(R.string.drive_delete_history), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    history?.let { listed ->
        AlertDialog(onDismissRequest = { if (!busy) history = null }, title = { Text(stringResource(R.string.drive_restore)) },
            text = {
                if (listed.snapshots.isEmpty()) Text(stringResource(R.string.drive_no_history))
                else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(listed.snapshots, key = { it.fileId }) { snapshot ->
                        TextButton(enabled = enabled, onClick = { execute {
                            val ciphertext = browser.download(listed, snapshot)
                            onRestoreCiphertext(ciphertext)
                            history = null; message = R.string.drive_restore_ready
                        } }) {
                            Column {
                                Text(snapshotDate(snapshot.createdAtEpochSeconds, locale) ?: stringResource(R.string.drive_unknown_date))
                                Text(stringResource(R.string.drive_snapshot_details, snapshot.revision, snapshot.sourceId.toString().take(8)))
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(enabled = enabled, onClick = { history = null }) { Text(stringResource(R.string.drive_close)) } })
    }
    confirmRevoke?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) confirmRevoke = null }, title = { Text(stringResource(R.string.drive_revoke)) },
            text = { Text(stringResource(R.string.drive_revoke_explanation)) },
            confirmButton = { TextButton(enabled = enabled, onClick = { confirmRevoke = null; execute {
                controller.revoke(selected, authorization::revokeAccess); history = null; message = R.string.drive_revoked
            } }) { Text(stringResource(R.string.drive_revoke)) } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.drive_cancel)) } })
    }
    confirmDelete?.let { selected ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.drive_delete_history)) },
            text = { Text(stringResource(R.string.drive_delete_explanation)) },
            confirmButton = { TextButton(enabled = enabled, onClick = { confirmDelete = null; execute {
                browser.deleteHistory(selected); history = null; message = R.string.drive_history_deleted
            } }) { Text(stringResource(R.string.drive_delete_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.drive_cancel)) } })
    }
}

private fun DriveSyncPhase.label(): Int = when (this) {
    DriveSyncPhase.DISCONNECTED -> R.string.drive_disconnected
    DriveSyncPhase.WAITING_FOR_UNLOCK -> R.string.drive_wait_unlock
    DriveSyncPhase.NEEDS_BACKUP_PASSWORD -> R.string.drive_wait_password
    DriveSyncPhase.PENDING -> R.string.drive_pending
    DriveSyncPhase.UPLOADING -> R.string.drive_uploading
    DriveSyncPhase.UPLOADED -> R.string.drive_uploaded
    DriveSyncPhase.NEEDS_CONSENT -> R.string.drive_auth_required
    DriveSyncPhase.UNAVAILABLE -> R.string.drive_unavailable
    DriveSyncPhase.RETRYING -> R.string.drive_retrying
    DriveSyncPhase.FAILED -> R.string.drive_operation_failed
}

private fun snapshotDate(epochSeconds: Long, locale: java.util.Locale): String? = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
} catch (_: java.time.DateTimeException) { null }
