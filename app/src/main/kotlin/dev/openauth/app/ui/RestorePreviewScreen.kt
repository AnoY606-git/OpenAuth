package dev.openauth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openauth.app.R
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.restore.RestorePlan
import dev.openauth.core.restore.RestoreAccountMetadata

@Composable
fun RestorePreviewScreen(plan: RestorePlan, mode: RestoreMode, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit, recovery: Boolean = false) {
    var replaceConfirmed by remember(plan) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_preview_counts, plan.added, plan.duplicates, plan.result.entries.size))
            if (recovery) Text(stringResource(R.string.backup_recovery_explanation))
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(plan.accounts) { account ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.backup_incoming), style = MaterialTheme.typography.labelLarge)
                        AccountMetadata(account.incoming)
                        account.retained?.let {
                            Text(stringResource(R.string.backup_duplicate_retained), style = MaterialTheme.typography.labelLarge)
                            AccountMetadata(it)
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (plan.hotpWarnings.isNotEmpty()) Text(stringResource(R.string.backup_hotp_warning), color = MaterialTheme.colorScheme.error)
            if (mode == RestoreMode.REPLACE) {
                Text(stringResource(R.string.backup_replace_warning))
                Row { Checkbox(replaceConfirmed, { replaceConfirmed = it }, enabled = !busy)
                    Text(stringResource(R.string.backup_replace_confirm), Modifier.padding(top = 12.dp)) }
            }
        } },
        confirmButton = { Button(onClick = onConfirm, enabled = !busy && (mode != RestoreMode.REPLACE || replaceConfirmed)) { Text(stringResource(R.string.backup_restore_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun AccountMetadata(value: RestoreAccountMetadata) {
    Text(value.issuer, style = MaterialTheme.typography.titleSmall)
    Text(value.accountName)
    Text("${value.type} · ${value.algorithm} · ${value.digits}", style = MaterialTheme.typography.bodySmall)
    Text(stringResource(if (value.type == dev.openauth.core.model.OtpType.HOTP) R.string.backup_preview_counter else R.string.backup_preview_period,
        if (value.type == dev.openauth.core.model.OtpType.HOTP) value.nextCounter else value.periodSeconds.toLong()), style = MaterialTheme.typography.bodySmall)
    value.groupName?.let { Text(stringResource(R.string.backup_preview_group, it), style = MaterialTheme.typography.bodySmall) }
    if (value.favorite) Text(stringResource(R.string.favorites), style = MaterialTheme.typography.bodySmall)
}
