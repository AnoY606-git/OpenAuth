package dev.openauth.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openauth.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    @Test fun explicitLanguageChangesAndroidResourcesAndSystemRestoresDeviceLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val english = AppLanguage.contextFor(context, LanguageMode.ENGLISH)
        val russian = AppLanguage.contextFor(context, LanguageMode.RUSSIAN)
        assertEquals("en", english.resources.configuration.locales[0].language)
        assertEquals("ru", russian.resources.configuration.locales[0].language)
        assertNotEquals(english.getString(R.string.settings), russian.getString(R.string.settings))
        assertEquals(android.content.res.Resources.getSystem().configuration.locales[0].language,
            AppLanguage.contextFor(context, LanguageMode.SYSTEM).resources.configuration.locales[0].language)
    }
}
