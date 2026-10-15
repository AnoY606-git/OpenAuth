package dev.openauth.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openauth.app.R

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(themeMode: ThemeMode, onTheme: (ThemeMode) -> Unit, language: LanguageMode = LanguageMode.SYSTEM, onLanguage: (LanguageMode) -> Unit = {}, content: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode -> FilterChip(selected = themeMode == mode, onClick = { onTheme(mode) }, label = { Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })) }) }
        }
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageMode.entries.forEach { mode -> FilterChip(selected = language == mode, onClick = { onLanguage(mode) }, label = {
                Text(stringResource(when (mode) { LanguageMode.SYSTEM -> R.string.language_system; LanguageMode.RUSSIAN -> R.string.language_russian; LanguageMode.ENGLISH -> R.string.language_english }))
            }) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyLarge)
            }
        }
        content()
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
