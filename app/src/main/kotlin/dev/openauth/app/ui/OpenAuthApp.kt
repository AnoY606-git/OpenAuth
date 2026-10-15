package dev.openauth.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openauth.app.R
import dev.openauth.app.clipboard.SensitiveClipboard
import dev.openauth.app.clipboard.ClipboardExpiryEffect
import dev.openauth.app.scanner.QrImportFlow
import dev.openauth.app.scanner.QrScannerScreen
import dev.openauth.app.ui.graphics.*
import dev.openauth.app.ui.theme.OpenAuthTheme
import dev.openauth.app.ui.motion.OpenAuthMotion
import dev.openauth.app.vault.*
import dev.openauth.core.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination(val label: Int, val symbol: Symbol) {
    CODES(R.string.codes, Symbol.CODES), BACKUPS(R.string.backups, Symbol.BACKUP), SETTINGS(R.string.settings, Symbol.SETTINGS)
}

/** Слоты подключают файловые копии и Drive, сохраняя единый shell, тему и защиту сессии. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAuthApp(
    viewModel: OpenAuthViewModel,
    onUnlock: suspend (initialize: Boolean) -> Unit,
    backupContent: @Composable () -> Unit = { BackupConnectionPending() },
    settingsContent: @Composable () -> Unit = {},
    clock: OtpClock = remember { OtpClock() },
    importFlow: QrImportFlow = remember { QrImportFlow() },
) {
    val context = LocalContext.current
    val clipboard = remember(context.applicationContext) { SensitiveClipboard(context) }
    ClipboardExpiryEffect(clipboard)
    val preferences = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("theme", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)) }
    val language = remember(context) { AppLanguage.selected(context) }
    val dark = when (theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        val activity = context.findActivity()
        activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    OpenAuthTheme(darkTheme = dark) {
        val state by viewModel.vaultState.collectAsStateWithLifecycle()
        var destination by remember { mutableStateOf(Destination.CODES) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<VaultFailure?>(null) }
        val scope = rememberCoroutineScope()
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column { Text("Open Auth", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(destination.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }, actions = { if (state is VaultState.Unlocked) LabeledIconButton(R.string.lock, Symbol.LOCK) { viewModel.repository.lock() } })
            },
            bottomBar = {
                NavigationBar { Destination.entries.forEach { item ->
                    NavigationBarItem(selected = destination == item, onClick = { destination = item }, icon = { UiSymbol(item.symbol) }, label = { Text(stringResource(item.label), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                } }
            },
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                // При lock ветка с секретами удаляется без exit-анимации и без сохранения старого кадра.
                when (destination) {
                    Destination.CODES -> {
                        val current = state
                        if (current is VaultState.Unlocked) UnlockedAccounts(viewModel, current.snapshot, clock, importFlow, clipboard)
                        else LockedScreen(current, busy, error) {
                            if (!busy) scope.launch {
                                busy = true; error = null
                                try { onUnlock(current == VaultState.Uninitialized) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: VaultException) { error = failure.reason }
                                catch (_: Exception) { error = VaultFailure.STORAGE_UNAVAILABLE }
                                finally { busy = false }
                            }
                        }
                    }
                    Destination.BACKUPS -> backupContent()
                    Destination.SETTINGS -> SettingsScreen(theme, { next -> theme = next; preferences.edit().putString("theme", next.name).apply() },
                        language = language, onLanguage = { next ->
                            AppLanguage.save(context, next)
                            viewModel.repository.lock()
                            context.findActivity()?.recreate()
                        }, content = settingsContent)
                }
            }
        }
    }
}

@Composable
private fun LockedScreen(state: VaultState, busy: Boolean, error: VaultFailure?, onUnlock: () -> Unit) {
    val first = state == VaultState.Uninitialized
    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        OpenAuthIllustration(if (first) IllustrationKind.WELCOME else IllustrationKind.LOCKED, Modifier.widthIn(max = 320.dp))
        Text(stringResource(if (first) R.string.welcome_title else R.string.locked_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(if (first) R.string.welcome_body else R.string.locked_body), modifier = Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val failure = error ?: (state as? VaultState.Unavailable)?.reason
        failure?.let { Text(stringResource(failureResource(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp)) }
        Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("unlock-vault"), contentPadding = PaddingValues(16.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else UiSymbol(Symbol.LOCK)
            Spacer(Modifier.width(10.dp)); Text(stringResource(if (first) R.string.create_vault else R.string.unlock))
        }
    }
}

@Composable
private fun UnlockedAccounts(viewModel: OpenAuthViewModel, snapshot: VaultSnapshot, clock: OtpClock, importFlow: QrImportFlow, clipboard: SensitiveClipboard) {
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val generator = remember { VisibleCodeGenerator() }
    var epoch by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var visible by remember { mutableStateOf(emptySet<UUID>()) }
    var codes by remember { mutableStateOf(emptyMap<UUID, String>()) }
    var hotpCodes by remember { mutableStateOf(emptyMap<UUID, String>()) }
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<UUID?>(null) }
    var sort by remember { mutableStateOf(AccountSort.ISSUER) }
    var editor by remember { mutableStateOf<AccountDraft?>(null) }
    var scanner by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var importPreview by remember { mutableStateOf(emptyList<OtpEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val deleteId by viewModel.pendingDelete.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { viewModel.cancelDelete(); importFlow.clear() } }
    LaunchedEffect(lifecycle, clock) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { clock.ticks().collect { epoch = it } }
    }
    LaunchedEffect(snapshot, visible, epoch, scanner) {
        if (scanner) { codes = emptyMap(); return@LaunchedEffect }
        val computed = withContext(Dispatchers.Default) { generator.generate(snapshot.entries, visible, epoch) }
        if ((viewModel.vaultState.value as? VaultState.Unlocked)?.snapshot === snapshot) codes = computed
    }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    fun perform(invalid: Int = R.string.operation_failed, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { error = invalid }
            catch (_: Exception) { error = R.string.operation_failed }
            finally { busy = false }
        }
    }
    fun cancelScanner() {
        importFlow.clear()
        importPreview = emptyList()
        scanner = false
        error = null
    }
    BackHandler(enabled = scanner) { cancelScanner() }
    if (scanner) {
        QrScannerScreen(onResult = { raw ->
            scanner = false
            try {
                importFlow.accept(raw)
                importPreview = importFlow.preview
                error = if (importFlow.waitingForParts) R.string.migration_wait else null
            } catch (_: IllegalArgumentException) { error = R.string.invalid_qr }
        }, onCancel = { cancelScanner() }, onManual = { cancelScanner(); editor = AccountDraft() })
    } else Column(Modifier.fillMaxSize()) {
        if (error != null && editor == null) Text(stringResource(error!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        AnimatedContent(targetState = copied, transitionSpec = { fadeIn(tween(OpenAuthMotion.ENTER_MILLIS)) togetherWith fadeOut(tween(OpenAuthMotion.EXIT_MILLIS)) }, label = "copy feedback") { show ->
            if (show) Text(stringResource(R.string.copied), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            AccountsScreen(snapshot, codes + hotpCodes, epoch, query, favorites, group, sort,
                onQuery = { query = it }, onFavorites = { favorites = it }, onGroup = { group = it }, onSort = { sort = it },
                onAdd = { error = null; editor = AccountDraft() }, onScan = { error = null; scanner = true }, onNewGroup = { groupName = ""; groupDialog = true; error = null },
                onCopy = { id -> (codes[id] ?: hotpCodes[id])?.let { code -> if (viewModel.vaultState.value is VaultState.Unlocked) { clipboard.copyCode(code); copied = true } } },
                onFavorite = { id -> perform { viewModel.toggleFavorite(id) } }, onEdit = { id -> error = null; editor = snapshot.entries.first { it.id == id }.toDraft() },
                onHotp = { id -> perform { val code = viewModel.issueHotp(id); if (viewModel.vaultState.value is VaultState.Unlocked) hotpCodes = hotpCodes + (id to code) } },
                onVisibleIds = { visible = it })
        }
    }
    editor?.let { draft -> AccountEditor(draft, snapshot.groups, busy, error,
        onSave = { value -> perform(R.string.invalid_account) { viewModel.save(value); value.id?.let { hotpCodes = hotpCodes - it }; editor = null } },
        onDismiss = { editor = null; error = null },
        onDelete = draft.id?.let { id -> { editor = null; viewModel.requestDelete(id) } }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = viewModel::cancelDelete,
        title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { perform { viewModel.confirmDelete(id); hotpCodes = hotpCodes - id } }, enabled = !busy, modifier = Modifier.testTag("confirm-delete")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) } }) }
    if (groupDialog) AlertDialog(onDismissRequest = { if (!busy) groupDialog = false },
        title = { Text(stringResource(R.string.new_group)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(groupName, { groupName = it.take(256) }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true); error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = { perform(R.string.invalid_group) { group = viewModel.createGroup(groupName); groupDialog = false } }, enabled = !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { groupDialog = false; error = null }) { Text(stringResource(R.string.cancel)) } })
    if (importPreview.isNotEmpty()) AlertDialog(onDismissRequest = { if (!busy) { importFlow.clear(); importPreview = emptyList() } },
        title = { Text(stringResource(R.string.import_title)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { Text(stringResource(R.string.import_body)) }
            items(importPreview, key = { it.id }) { entry -> ListItem(headlineContent = { Text(entry.issuer) }, supportingContent = { Text(entry.accountName) }) }
            error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) } }
        } },
        confirmButton = { TextButton(onClick = { perform { viewModel.addImported(importPreview); importFlow.clear(); importPreview = emptyList() } }, enabled = !busy) { Text(stringResource(R.string.import_confirm, importPreview.size)) } },
        dismissButton = { TextButton(onClick = { importFlow.clear(); importPreview = emptyList() }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun BackupConnectionPending() {
    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        OpenAuthIllustration(IllustrationKind.BACKUP, Modifier.widthIn(max = 300.dp))
        Text(stringResource(R.string.backup_connecting), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.backup_connecting_body), modifier = Modifier.padding(top = 16.dp))
    }
}

private fun failureResource(reason: VaultFailure): Int = when (reason) {
    VaultFailure.DEVICE_NOT_SECURE -> R.string.failure_device
    VaultFailure.KEY_UNAVAILABLE, VaultFailure.KEY_INVALIDATED -> R.string.failure_key
    VaultFailure.CORRUPT_STORAGE -> R.string.failure_corrupt
    VaultFailure.AUTHENTICATION_CANCELLED -> R.string.failure_cancelled
    VaultFailure.AUTHENTICATION_REQUIRED, VaultFailure.LOCKED -> R.string.failure_auth
    VaultFailure.ALREADY_INITIALIZED -> R.string.failure_exists
    VaultFailure.STORAGE_UNAVAILABLE -> R.string.failure_storage
}

private fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
