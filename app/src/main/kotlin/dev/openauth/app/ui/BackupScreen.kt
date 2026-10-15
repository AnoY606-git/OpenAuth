package dev.openauth.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.openauth.app.drive.DriveFeature
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.openauth.app.R
import dev.openauth.app.backup.*
import dev.openauth.app.vault.VaultRepository
import dev.openauth.app.vault.VaultState
import dev.openauth.core.backup.BackupCredential
import dev.openauth.core.backup.BackupException
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.restore.RestorePlan
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class CredentialAction { CONFIGURE, ROTATE, RESTORE }

@Composable
fun BackupScreen(
    coordinator: BackupCoordinator,
    repository: VaultRepository,
    onUnlock: suspend () -> Unit,
    cloudContent: @Composable () -> Unit = {},
    onAuthenticateRecovery: suspend () -> Unit = { throw dev.openauth.app.vault.VaultException(dev.openauth.app.vault.VaultFailure.AUTHENTICATION_REQUIRED) },
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val gateway = remember(context) { FileBackupGateway(context.contentResolver) }
    val scope = rememberCoroutineScope()
    val vault by repository.state.collectAsState()
    val state by coordinator.state.collectAsState()
    val pendingImport by coordinator.files.pendingImport.collectAsState()
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var action by remember { mutableStateOf<CredentialAction?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var recovery by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }

    fun execute(onComplete: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) { onComplete(); return }
        busy = true; message = null
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BackupException) { message = R.string.backup_credential_or_file_error }
            catch (failure: BackupFlowException) { message = if (failure.reason == BackupFlowFailure.STALE_PREVIEW || failure.reason == BackupFlowFailure.LOCKED_OR_CHANGED) R.string.backup_stale_error else R.string.backup_operation_error }
            catch (_: Exception) { message = R.string.backup_operation_error }
            finally { busy = false }
        }.also { it.invokeOnCompletion { onComplete() } }
    }
    suspend fun ensureUnlocked() {
        if (repository.state.value !is VaultState.Unlocked) onUnlock()
        if (repository.state.value !is VaultState.Unlocked) throw BackupFlowException(BackupFlowFailure.LOCKED_OR_CHANGED)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val encrypted = coordinator.files.takeExport()
        if (uri != null && encrypted != null) execute { gateway.write(uri, encrypted); message = R.string.backup_export_success }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        coordinator.files.stageImport(uri)
    }
    LaunchedEffect(vault) {
        if (vault !is VaultState.Unlocked) { password = ""; confirmation = ""; action = null; plan = null; coordinator.cancelRestore() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                operation?.cancel()
                password = ""; confirmation = ""; action = null; plan = null
                scope.launch { coordinator.cancelRestore() }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.backup_description))
        Text(stringResource(if (!DriveFeature.enabled) R.string.backup_file_status else when (state) {
            BackupState.NeverCreated -> R.string.backup_state_never
            is BackupState.Pending -> R.string.backup_state_pending
            is BackupState.Uploaded -> R.string.backup_state_uploaded
            is BackupState.Failure -> R.string.backup_state_failure
        }))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { execute {
            ensureUnlocked(); action = if (coordinator.isConfigured()) CredentialAction.ROTATE else CredentialAction.CONFIGURE
        } }) { Text(stringResource(R.string.backup_configure)) }
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { execute {
            ensureUnlocked()
            if (!coordinator.isConfigured()) { action = CredentialAction.CONFIGURE; return@execute }
            coordinator.files.stageExport(coordinator.exportSnapshot())
            createDocument.launch("OpenAuth-${LocalDate.now()}.oab")
        } }) { Text(stringResource(R.string.backup_export)) }
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openDocument.launch(arrayOf("application/octet-stream", "application/x-openauth", "*/*")) }) {
            Text(stringResource(R.string.backup_open))
        }
        if (pendingImport != null) {
            Text(stringResource(R.string.backup_file_selected))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { execute { ensureUnlocked(); recovery = false; action = CredentialAction.RESTORE } }) {
                Text(stringResource(R.string.backup_continue_restore))
            }
            if (vault !is VaultState.Unlocked && vault != VaultState.Uninitialized) {
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    recovery = true; mode = RestoreMode.REPLACE; action = CredentialAction.RESTORE
                }) { Text(stringResource(R.string.backup_recover_unavailable)) }
            }
            TextButton(enabled = !busy, onClick = { coordinator.files.clearImport() }) { Text(stringResource(R.string.backup_cancel)) }
        }
        cloudContent()
    }
    if (action != null) AlertDialog(
        onDismissRequest = { if (!busy) { action = null; password = ""; confirmation = "" } },
        title = { Text(stringResource(if (action == CredentialAction.RESTORE) R.string.backup_restore_password else R.string.backup_set_password)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = pin, enabled = !busy, role = Role.Checkbox, onValueChange = { pin = it; password = ""; confirmation = "" })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(pin, onCheckedChange = null, enabled = !busy)
                Text(stringResource(R.string.backup_use_pin), Modifier.weight(1f).padding(start = 12.dp))
            }
            Text(stringResource(if (pin) R.string.backup_pin_warning else R.string.backup_password_rule))
            OutlinedTextField(value = password, onValueChange = { if (it.length <= 1024) password = it }, enabled = !busy,
                label = { Text(stringResource(if (pin) R.string.backup_pin_label else R.string.backup_password_label)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                keyboardOptions = KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password))
            if (action != CredentialAction.RESTORE) {
                val mismatch = confirmation.isNotEmpty() && confirmation != password
                OutlinedTextField(value = confirmation, onValueChange = { if (it.length <= 1024) confirmation = it }, enabled = !busy,
                    label = { Text(stringResource(if (pin) R.string.backup_pin_confirmation else R.string.backup_password_confirmation)) },
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.backup_confirmation_mismatch)) }) else null,
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backup-password-confirmation"),
                    keyboardOptions = KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password))
            }
            if (action == CredentialAction.RESTORE && recovery) Text(stringResource(R.string.backup_recovery_explanation))
            else if (action == CredentialAction.RESTORE) {
                Column(Modifier.selectableGroup()) {
                    listOf(RestoreMode.MERGE to R.string.backup_merge, RestoreMode.REPLACE to R.string.backup_replace).forEach { (option, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = mode == option, enabled = !busy, role = Role.RadioButton, onClick = { mode = option })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = null, enabled = !busy)
                            Text(stringResource(label), Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                }
            } else Text(stringResource(R.string.backup_rotation_notice))
        } },
        confirmButton = { Button(enabled = !busy && password.isNotEmpty() && (action == CredentialAction.RESTORE || confirmation == password), onClick = {
            val selected = action ?: return@Button
            if (selected != CredentialAction.RESTORE && confirmation != password) return@Button
            val chars = password.toCharArray(); password = ""; confirmation = ""
            val credential = try { if (pin) BackupCredential.pin(chars) else BackupCredential.password(chars) }
            catch (_: BackupException) { message = R.string.backup_credential_rule_error; null }
            finally { chars.fill('\u0000') }
            if (credential != null) {
                action = null
                execute(onComplete = credential::close) {
                    try {
                        if (!recovery || selected != CredentialAction.RESTORE) ensureUnlocked()
                        when (selected) {
                            CredentialAction.CONFIGURE -> { coordinator.configure(credential); message = R.string.backup_configured }
                            CredentialAction.ROTATE -> { coordinator.rotate(credential); message = R.string.backup_configured }
                            CredentialAction.RESTORE -> {
                                val ciphertext = coordinator.files.readImport(gateway::read)
                                plan = if (recovery) coordinator.previewRecoveryRestore(ciphertext, credential) else coordinator.previewRestore(ciphertext, credential, mode)
                            }
                        }
                    } finally { credential.close() }
                }
            }
        }) { Text(stringResource(R.string.backup_continue)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { action = null; password = ""; confirmation = "" }) { Text(stringResource(R.string.backup_cancel)) } },
    )
    plan?.let { ready -> RestorePreviewScreen(ready, mode, busy,
        onConfirm = { execute { coordinator.commitRestore(ready, onAuthenticateRecovery); plan = null; coordinator.files.clearImport(); message = R.string.backup_restore_success } },
        onCancel = { plan = null; scope.launch { coordinator.cancelRestore() } },
        recovery = recovery,
    ) }
}
