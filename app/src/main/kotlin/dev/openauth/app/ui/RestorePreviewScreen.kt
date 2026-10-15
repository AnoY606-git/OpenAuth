package dev.openauth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openauth.app.R
import dev.openauth.core.restore.RestoreMode
import dev.openauth.core.restore.RestorePlan

@Composable
fun RestorePreviewScreen(plan: RestorePlan, mode: RestoreMode, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var replaceConfirmed by remember(plan) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_preview_counts, plan.added, plan.duplicates, plan.result.entries.size))
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
