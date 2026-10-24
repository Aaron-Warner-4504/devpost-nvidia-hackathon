package ai.enthral.sentinel.data.system

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Per-app language. Changing it recreates the activity, so the whole UI switches immediately. */
interface LocaleController {
    /** "en" or "hi". */
    fun current(): String
    fun set(tag: String)
}

class AppCompatLocaleController : LocaleController {
    override fun current(): String {
        val explicit = AppCompatDelegate.getApplicationLocales()
        val lang = if (explicit.isEmpty) Locale.getDefault().language else explicit[0]?.language
        return if (lang == "hi") "hi" else "en"
    }

    override fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
