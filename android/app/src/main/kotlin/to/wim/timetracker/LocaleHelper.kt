package to.wim.timetracker

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    /**
     * Resolves a stored locale tag to one of the app's translations by its
     * language subtag. Both apps write their own tags into the shared sync
     * page — this app stores `en`/`nl`, the Windows app stores `en-GB`/`nl-BE` —
     * so a tag pulled from the other device has to resolve by language rather
     * than by exact match. `Locale("nl-BE")` builds a locale whose *language*
     * is the whole string, which matches no resource folder and silently falls
     * back to English.
     */
    fun resolve(localeCode: String): Locale? {
        val lang = localeCode.lowercase().substringBefore('-').substringBefore('_')
        return when (lang) {
            "en" -> Locale.ENGLISH
            "nl" -> Locale("nl")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "es" -> Locale("es")
            "it" -> Locale.ITALIAN
            else -> null
        }
    }

    fun wrap(context: Context, localeCode: String): Context {
        if (localeCode == "system" || localeCode.isBlank()) {
            return context
        }
        val locale = resolve(localeCode) ?: return context
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
