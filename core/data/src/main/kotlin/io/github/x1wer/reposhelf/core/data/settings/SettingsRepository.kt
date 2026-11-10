// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.x1wer.reposhelf.core.update.CheckFrequency
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Az utolsó háttérben futott ellenőrzés összegzése (a felület mutatja, mert a futás ideje nem garantált; ARCH 12.). */
data class BackgroundRun(val at: Instant, val checked: Int, val updates: Int, val notChecked: Int)

/**
 * A globális beállítások tartós tárolása (Preferences DataStore, docs/ARCHITECTURE.md 4. pont). Titok nincs benne.
 * Ismeretlen vagy sérült érték esetén a biztonságos alapérték érvényes (C. 12.5.).
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<GlobalSettings> = store.data.map { it.toSettings() }

    val lastBackgroundRun: Flow<BackgroundRun?> = store.data.map { prefs ->
        prefs[LAST_RUN_AT]?.let { at -> BackgroundRun(Instant.ofEpochMilli(at), prefs[LAST_RUN_CHECKED] ?: 0, prefs[LAST_RUN_UPDATES] ?: 0, prefs[LAST_RUN_NOT_CHECKED] ?: 0) }
    }

    suspend fun current(): GlobalSettings = settings.first()

    suspend fun update(transform: (GlobalSettings) -> GlobalSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[FREQUENCY] = next.checkFrequency.name
            prefs[CHECK_UNMETERED] = next.checkOnlyUnmetered
            prefs[AUTO_DOWNLOAD] = next.autoDownload
            prefs[AUTO_DOWNLOAD_METERED] = next.autoDownloadOnMetered
            prefs[AUTO_INSTALL] = next.autoInstall
            prefs[PRERELEASES] = next.includePrereleases
            prefs[RELEASE_DELAY_DAYS] = next.releaseDelayDays.coerceIn(0, 30)
            prefs[NOTIFY_UPDATES] = next.notifyUpdates
            prefs[UPDATE_OWNERSHIP] = next.requestUpdateOwnership
        }
    }

    suspend fun recordBackgroundRun(run: BackgroundRun) {
        store.edit { prefs ->
            prefs[LAST_RUN_AT] = run.at.toEpochMilli()
            prefs[LAST_RUN_CHECKED] = run.checked
            prefs[LAST_RUN_UPDATES] = run.updates
            prefs[LAST_RUN_NOT_CHECKED] = run.notChecked
        }
    }

    /** Megkérdeztük-e már az értesítési engedélyt (hogy ne zaklassuk a felhasználót; ARCH 12.). */
    suspend fun notificationPermissionAsked(): Boolean = store.data.first()[NOTIFICATION_ASKED] ?: false

    suspend fun markNotificationPermissionAsked() {
        store.edit { it[NOTIFICATION_ASKED] = true }
    }

    private fun Preferences.toSettings(): GlobalSettings {
        val defaults = GlobalSettings()
        return GlobalSettings(
            checkFrequency = this[FREQUENCY]?.let { name -> CheckFrequency.entries.firstOrNull { it.name == name } } ?: defaults.checkFrequency,
            checkOnlyUnmetered = this[CHECK_UNMETERED] ?: defaults.checkOnlyUnmetered,
            autoDownload = this[AUTO_DOWNLOAD] ?: defaults.autoDownload,
            autoDownloadOnMetered = this[AUTO_DOWNLOAD_METERED] ?: defaults.autoDownloadOnMetered,
            autoInstall = this[AUTO_INSTALL] ?: defaults.autoInstall,
            includePrereleases = this[PRERELEASES] ?: defaults.includePrereleases,
            releaseDelayDays = (this[RELEASE_DELAY_DAYS] ?: defaults.releaseDelayDays).coerceIn(0, 30),
            notifyUpdates = this[NOTIFY_UPDATES] ?: defaults.notifyUpdates,
            requestUpdateOwnership = this[UPDATE_OWNERSHIP] ?: defaults.requestUpdateOwnership,
        )
    }

    companion object {
        private val FREQUENCY = stringPreferencesKey("check_frequency")
        private val CHECK_UNMETERED = booleanPreferencesKey("check_only_unmetered")
        private val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        private val AUTO_DOWNLOAD_METERED = booleanPreferencesKey("auto_download_metered")
        private val AUTO_INSTALL = booleanPreferencesKey("auto_install")
        private val PRERELEASES = booleanPreferencesKey("include_prereleases")
        private val RELEASE_DELAY_DAYS = intPreferencesKey("release_delay_days")
        private val NOTIFY_UPDATES = booleanPreferencesKey("notify_updates")
        private val UPDATE_OWNERSHIP = booleanPreferencesKey("request_update_ownership")
        private val LAST_RUN_AT = longPreferencesKey("last_background_run_at")
        private val LAST_RUN_CHECKED = intPreferencesKey("last_background_run_checked")
        private val LAST_RUN_UPDATES = intPreferencesKey("last_background_run_updates")
        private val LAST_RUN_NOT_CHECKED = intPreferencesKey("last_background_run_not_checked")
        private val NOTIFICATION_ASKED = booleanPreferencesKey("notification_permission_asked")

        fun create(context: Context): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("settings") })
    }
}
