// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A felület témája (C. 22.2.): a rendszert követi, vagy mindig világos / sötét. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A felület nyelve (C. 22.2.): a rendszeré, angol vagy magyar. */
enum class AppLanguage(val tag: String?) { SYSTEM(null), ENGLISH("en"), HUNGARIAN("hu") }

/**
 * Megjelenési beállítások (D-132). Szinkron módon olvasható tárolóban (SharedPreferences) vannak, mert a téma az első
 * képkocka előtt, a nyelv pedig már az `attachBaseContext`-ben kell; a mentés (D-122) ezeket sem viszi más eszközre.
 */
class AppearanceSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(parse(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM))
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))

    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
        _dynamicColor.value = enabled
    }

    companion object {
        internal const val PREFS = "appearance"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"

        private inline fun <reified T : Enum<T>> parse(value: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: default
    }
}

/**
 * Az alkalmazás saját nyelve (D-132). Android 13-tól a rendszer alkalmazásonkénti nyelvbeállítását használja
 * (`LocaleManager`; a rendszer Beállításokban is megjelenik, a `locales_config.xml` alapján). Alatta (Android 9–12) a
 * választás helyben tárolódik, és az activity, valamint az alkalmazás kontextusa ezzel a nyelvvel jön létre, így a
 * háttérben készülő értesítések is ezen a nyelven szólnak.
 */
object AppLocales {
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            fromTag(if (locales == null || locales.isEmpty) null else locales[0].language)
        } else {
            stored(context)
        }

    /** Alkalmazza a választott nyelvet; az activity újra létrejön. */
    // A `commit()` szándékos: az activity rögtön újra létrejön, és az `attachBaseContext` már az új értéket olvassa.
    @SuppressLint("ApplySharedPref")
    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // A rendszer tárolja, és maga hozza létre újra az activityket.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
            return
        }
        prefs(activity).edit(commit = true) { putString(KEY_LANGUAGE, language.name) }
        // Az alkalmazás kontextusának erőforrásai is az új nyelvet használják (háttér-értesítések), újraindítás nélkül.
        val app = activity.applicationContext
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(configurationFor(app, language), app.resources.displayMetrics)
        activity.recreate()
    }

    /** Android 9–12: a kontextus a tárolt nyelvvel (`attachBaseContext`). Android 13-tól és „rendszer” választásnál változatlan. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = stored(base)
        if (language.tag == null) return base
        return base.createConfigurationContext(configurationFor(base, language))
    }

    internal fun fromTag(language: String?): AppLanguage =
        AppLanguage.entries.firstOrNull { it.tag != null && it.tag == language } ?: AppLanguage.SYSTEM

    private fun stored(context: Context): AppLanguage =
        AppLanguage.entries.firstOrNull { it.name == prefs(context).getString(KEY_LANGUAGE, null) } ?: AppLanguage.SYSTEM

    private fun configurationFor(context: Context, language: AppLanguage): Configuration {
        val config = Configuration(context.resources.configuration)
        // „Rendszer”: a készülék nyelve (a Locale alapértéke a korábbi választás miatt már eltérhet tőle).
        val locales = language.tag?.let { LocaleList.forLanguageTags(it) } ?: Resources.getSystem().configuration.locales
        config.setLocales(locales)
        Locale.setDefault(locales[0])
        return config
    }

    private fun prefs(context: Context) = context.getSharedPreferences(AppearanceSettings.PREFS, Context.MODE_PRIVATE)
}
