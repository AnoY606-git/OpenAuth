package dev.openauth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.openauth.app.R
import dev.openauth.app.ui.graphics.*
import dev.openauth.core.model.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditor(
    initial: AccountDraft,
    groups: List<EntryGroup>,
    busy: Boolean,
    error: Int?,
    onSave: (AccountDraft) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // Только неперсистентное состояние: секрет не попадает в Bundle при повороте или смерти процесса.
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (initial.id == null) R.string.add_account else R.string.edit_account), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(draft.issuer, { draft = draft.copy(issuer = it.take(512)) }, label = { Text(stringResource(R.string.issuer)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(draft.accountName, { draft = draft.copy(accountName = it.take(512)) }, label = { Text(stringResource(R.string.account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("editor-account"), enabled = !busy)
            OutlinedTextField(draft.secret, { draft = draft.copy(secret = it.take(512)) }, label = { Text(stringResource(R.string.setup_key)) },
                supportingText = if (initial.id != null) { { Text(stringResource(R.string.keep_secret)) } } else null,
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("editor-secret"), enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Box {
                OutlinedButton(onClick = { showGroupMenu = true }, enabled = !busy) { Text(groups.firstOrNull { it.id == draft.groupId }?.name ?: stringResource(R.string.no_group)) }
                DropdownMenu(showGroupMenu, onDismissRequest = { showGroupMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.no_group)) }, onClick = { draft = draft.copy(groupId = null); showGroupMenu = false })
                    groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) }, onClick = { draft = draft.copy(groupId = group.id); showGroupMenu = false }) }
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.advanced)) }
            if (advanced) {
                Text(stringResource(R.string.otp_type), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OtpType.entries.forEach { type -> FilterChip(selected = draft.type == type, onClick = { draft = draft.copy(type = type, algorithm = if (type == OtpType.HOTP) HashAlgorithm.SHA1 else draft.algorithm) }, label = { Text(type.name) }, enabled = !busy) }
                }
                if (draft.type == OtpType.TOTP) {
                    Text(stringResource(R.string.algorithm), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HashAlgorithm.entries.forEach { algorithm ->
                        FilterChip(selected = draft.algorithm == algorithm, onClick = { draft = draft.copy(algorithm = algorithm) }, label = { Text(algorithm.name.replace("SHA", "SHA-")) }, enabled = !busy)
                    } }
                    OutlinedTextField(draft.period, { draft = draft.copy(period = it.take(3)) }, label = { Text(stringResource(R.string.period)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(draft.counter, { draft = draft.copy(counter = it.take(19)) }, label = { Text(stringResource(R.string.counter)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.digits), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf(6, 8).forEach { digits -> FilterChip(selected = draft.digits == digits, onClick = { draft = draft.copy(digits = digits) }, label = { Text(digits.toString()) }, enabled = !busy) } }
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor-error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onSave(draft) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("editor-save")) { Text(stringResource(R.string.save)) }
            }
            if (onDelete != null) TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag("editor-delete")) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun OtpEntry.toDraft() = AccountDraft(id, issuer, accountName, "", type, algorithm, digits, periodSeconds.toString(), nextCounter.toString(), groupId)
