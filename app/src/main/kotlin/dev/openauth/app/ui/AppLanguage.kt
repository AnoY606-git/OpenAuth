package dev.openauth.app.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** Единственное сохраняемое состояние языка — нечувствительный выбор пользователя. */
enum class LanguageMode { SYSTEM, RUSSIAN, ENGLISH }
object AppLanguage {
    fun selected(context: Context): LanguageMode = runCatching {
        LanguageMode.valueOf(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", LanguageMode.SYSTEM.name)!!)
    }.getOrDefault(LanguageMode.SYSTEM)
    fun save(context: Context, mode: LanguageMode) {
        check(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", mode.name).commit())
    }
    fun contextFor(context: Context, mode: LanguageMode = selected(context)): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(when (mode) {
            LanguageMode.SYSTEM -> Resources.getSystem().configuration.locales
            LanguageMode.RUSSIAN -> LocaleList(Locale.forLanguageTag("ru"))
            LanguageMode.ENGLISH -> LocaleList(Locale.forLanguageTag("en"))
        })
        return context.createConfigurationContext(configuration)
    }
}
