package dev.openauth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import dev.openauth.app.ui.motion.OpenAuthMotion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openauth.app.R
import dev.openauth.app.ui.graphics.*
import dev.openauth.core.model.*
import dev.openauth.core.otp.OtpEngine
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(
    snapshot: VaultSnapshot,
    codes: Map<UUID, String> = emptyMap(),
    epochSeconds: Long = 0,
    query: String = "",
    favoritesOnly: Boolean = false,
    groupId: UUID? = null,
    sort: AccountSort = AccountSort.ISSUER,
    onQuery: (String) -> Unit = {},
    onFavorites: (Boolean) -> Unit = {},
    onGroup: (UUID?) -> Unit = {},
    onSort: (AccountSort) -> Unit = {},
    onAdd: () -> Unit = {},
    onScan: () -> Unit = {},
    onNewGroup: () -> Unit = {},
    onCopy: (UUID) -> Unit = {},
    onFavorite: (UUID) -> Unit = {},
    onEdit: (UUID) -> Unit = {},
    onHotp: (UUID) -> Unit = {},
    onVisibleIds: (Set<UUID>) -> Unit = {},
) {
    val entries = remember(snapshot, query, favoritesOnly, groupId, sort) { selectAccounts(snapshot, query, favoritesOnly, groupId, sort) }
    val listState = rememberLazyListState()
    val currentVisibleCallback by rememberUpdatedState(onVisibleIds)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? UUID }.toSet() }
            .distinctUntilChanged().collect { currentVisibleCallback(it) }
    }
    var sortMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).imePadding()) {
        val shortWindow = maxHeight < 400.dp
        val largeFont = LocalDensity.current.fontScale >= 1.3f
        Column(Modifier.fillMaxSize()) {
            // Поиск и фильтры прокручиваются вместе с кодами: клавиатура и landscape не скрывают список.
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("accounts-list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "search", contentType = "search") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = query, onValueChange = onQuery,
                            modifier = Modifier.weight(1f).testTag("account-search"), singleLine = true,
                            placeholder = { Text(stringResource(R.string.search)) },
                            leadingIcon = { UiSymbol(Symbol.SEARCH) },
                            trailingIcon = if (query.isNotEmpty()) { { LabeledIconButton(R.string.clear_search, Symbol.CLOSE) { onQuery("") } } } else null,
                            shape = MaterialTheme.shapes.large)
                        Box {
                            LabeledIconButton(R.string.sort, Symbol.SORT, modifier = Modifier.padding(start = 4.dp)) { sortMenu = true }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                AccountSort.entries.forEach { value ->
                                    DropdownMenuItem(text = { Text(stringResource(sortLabel(value))) }, onClick = { onSort(value); sortMenu = false })
                                }
                            }
                        }
                    }
                }
                item(key = "filters", contentType = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") { FilterChip(selected = !favoritesOnly && groupId == null, onClick = { onFavorites(false); onGroup(null) }, label = { Text(stringResource(R.string.all_accounts)) }) }
                        item(key = "favorites") { FilterChip(selected = favoritesOnly, onClick = { onFavorites(!favoritesOnly) }, label = { Text(stringResource(R.string.favorites)) }, leadingIcon = { UiSymbol(Symbol.STAR, Modifier.size(16.dp), filled = favoritesOnly) }) }
                        items(snapshot.groups, key = { it.id }) { group -> FilterChip(selected = groupId == group.id, onClick = { onGroup(if (groupId == group.id) null else group.id) }, label = { Text(group.name) }) }
                        item(key = "new") { SuggestionChip(onClick = onNewGroup, label = { Text(stringResource(R.string.new_group)) }, icon = { UiSymbol(Symbol.ADD, Modifier.size(16.dp)) }) }
                    }
                }
                if (shortWindow) item(key = "actions", contentType = "actions") { AccountActions(largeFont, onScan, onAdd) }
                if (entries.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OpenAuthIllustration(IllustrationKind.EMPTY_VAULT, Modifier.widthIn(max = 240.dp))
                            Text(stringResource(if (snapshot.entries.isEmpty()) R.string.empty_title else R.string.no_results), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(if (snapshot.entries.isEmpty()) R.string.empty_body else R.string.no_results_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(entries, key = { it.id }, contentType = { it.type }) { entry ->
                        AccountCard(entry, codes[entry.id], epochSeconds, onCopy, onFavorite, onEdit, onHotp, Modifier.animateItem(fadeInSpec = tween(OpenAuthMotion.ENTER_MILLIS), placementSpec = tween(OpenAuthMotion.MOVE_MILLIS), fadeOutSpec = tween(OpenAuthMotion.EXIT_MILLIS)))
                    }
                }
            }
            if (!shortWindow) AccountActions(largeFont, onScan, onAdd, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountActions(largeFont: Boolean, onScan: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (largeFont) 1 else 2) {
        FilledTonalButton(onClick = onScan, modifier = (if (largeFont) Modifier.fillMaxWidth() else Modifier.weight(1f)).fillMaxRowHeight().heightIn(min = 48.dp).testTag("scan-account"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
            UiSymbol(Symbol.SCAN); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_qr))
        }
        Button(onClick = onAdd, modifier = (if (largeFont) Modifier.fillMaxWidth() else Modifier.weight(1f)).fillMaxRowHeight().heightIn(min = 48.dp).testTag("add-account"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
            UiSymbol(Symbol.ADD); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_account))
        }
    }
}

@Composable
private fun AccountCard(entry: OtpEntry, code: String?, epochSeconds: Long, onCopy: (UUID) -> Unit, onFavorite: (UUID) -> Unit, onEdit: (UUID) -> Unit, onHotp: (UUID) -> Unit, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val separateActions = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ServiceIcon(entry.issuer.ifEmpty { entry.accountName }, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(entry.issuer.ifEmpty { entry.accountName }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.accountName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!separateActions) AccountCardActions(entry, onFavorite, onEdit)
                    }
                    if (separateActions) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { AccountCardActions(entry, onFavorite, onEdit) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val formatted = code?.chunked(if (code.length == 8) 4 else 3)?.joinToString(" ") ?: "••• •••"
            val spoken = code?.toCharArray()?.joinToString(" ") ?: ""
            val description = stringResource(R.string.code_accessible, spoken)
            // Размер подбирается под все цифры, сохраняя системное увеличение шрифта и одну строку.
            BasicText(formatted, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace,
                fontSize = 34.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp, stepSize = 1.sp),
                maxLines = 1, softWrap = false,
                modifier = Modifier.fillMaxWidth().testTag("code-${entry.id}").semantics { contentDescription = description })
            if (entry.type == OtpType.TOTP) {
                val remaining = OtpEngine.remainingSeconds(entry.periodSeconds, epochSeconds.coerceAtLeast(0))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { remaining.toFloat() / entry.periodSeconds }, modifier = Modifier.weight(1f).height(3.dp))
                    Text(stringResource(R.string.seconds_left, remaining), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    LabeledIconButton(R.string.copy_code, Symbol.COPY, Modifier.testTag("copy-${entry.id}"), enabled = code != null) { onCopy(entry.id) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onHotp(entry.id) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.generate_hotp)) }
                    LabeledIconButton(R.string.copy_code, Symbol.COPY, Modifier.testTag("copy-${entry.id}"), enabled = code != null) { onCopy(entry.id) }
                }
            }
        }
    }
}

@Composable
private fun AccountCardActions(entry: OtpEntry, onFavorite: (UUID) -> Unit, onEdit: (UUID) -> Unit) {
    LabeledIconButton(if (entry.favorite) R.string.remove_favorite else R.string.add_favorite, Symbol.STAR,
        Modifier.testTag("favorite-${entry.id}"), filled = entry.favorite) { onFavorite(entry.id) }
    LabeledIconButton(R.string.edit_account, Symbol.EDIT, Modifier.testTag("edit-${entry.id}")) { onEdit(entry.id) }
}

@Composable
internal fun LabeledIconButton(label: Int, symbol: Symbol, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val description = stringResource(label)
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description }) { UiSymbol(symbol, filled = filled) }
}
internal fun sortLabel(sort: AccountSort): Int = when(sort) { AccountSort.ISSUER -> R.string.sort_issuer; AccountSort.ACCOUNT -> R.string.sort_account; AccountSort.ADDED -> R.string.sort_custom }
