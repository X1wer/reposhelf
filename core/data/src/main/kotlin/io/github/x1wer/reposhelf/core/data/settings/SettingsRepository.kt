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

/**
 * Egy elindított saját frissítés (C. 14.). A telepítés közben a rendszer leállítja az appot, ezért az eredményt az új
 * verzió első indításkor rögzíti (ARCH 14.). A [sessionId] a commit után kerül be (`null`: a commit előtt megszakadt).
 */
data class PendingSelfUpdate(
    val fromCode: Long,
    val fromName: String?,
    val toCode: Long,
    val toName: String?,
    val at: Instant,
    val sessionId: Int? = null,
    /** A háttérfeladat indította (automatikus frissítés), nem a felhasználó. */
    val automatic: Boolean = false,
)

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
            prefs[SELF_UPDATE_CHECK] = next.selfUpdateCheck
        }
    }

    suspend fun pendingSelfUpdate(): PendingSelfUpdate? {
        val prefs = store.data.first()
        val from = prefs[PENDING_FROM_CODE] ?: return null
        val to = prefs[PENDING_TO_CODE] ?: return null
        return PendingSelfUpdate(
            from, prefs[PENDING_FROM_NAME], to, prefs[PENDING_TO_NAME], Instant.ofEpochMilli(prefs[PENDING_AT] ?: 0),
            prefs[PENDING_SESSION], prefs[PENDING_AUTOMATIC] ?: false,
        )
    }

    suspend fun setPendingSelfUpdate(pending: PendingSelfUpdate?) {
        store.edit { prefs ->
            if (pending == null) {
                listOf(PENDING_FROM_CODE, PENDING_TO_CODE, PENDING_AT).forEach { prefs.remove(it) }
                listOf(PENDING_FROM_NAME, PENDING_TO_NAME).forEach { prefs.remove(it) }
                prefs.remove(PENDING_SESSION)
                prefs.remove(PENDING_AUTOMATIC)
            } else {
                prefs[PENDING_FROM_CODE] = pending.fromCode
                prefs[PENDING_TO_CODE] = pending.toCode
                prefs[PENDING_AT] = pending.at.toEpochMilli()
                pending.fromName?.let { prefs[PENDING_FROM_NAME] = it.take(100) } ?: prefs.remove(PENDING_FROM_NAME)
                pending.toName?.let { prefs[PENDING_TO_NAME] = it.take(100) } ?: prefs.remove(PENDING_TO_NAME)
                pending.sessionId?.let { prefs[PENDING_SESSION] = it } ?: prefs.remove(PENDING_SESSION)
                prefs[PENDING_AUTOMATIC] = pending.automatic
            }
        }
    }

    /** A saját repó rögzített stabil azonosítója és tulajdonosa (repó-eltérítés felismerése, C. 19.). */
    suspend fun selfRepoIdentity(): Pair<Long?, Long?> = store.data.first().let { it[SELF_REPO_ID] to it[SELF_OWNER_ID] }

    suspend fun setSelfRepoIdentity(repoId: Long, ownerId: Long?) {
        store.edit { prefs ->
            prefs[SELF_REPO_ID] = repoId
            ownerId?.let { prefs[SELF_OWNER_ID] = it }
        }
    }

    /** Megválaszolta-e már a felhasználó a saját frissítésről szóló semleges kérdést (opt-in, D-062). */
    val selfUpdatePromptAnswered: Flow<Boolean> = store.data.map { it[SELF_UPDATE_ASKED] ?: false }

    suspend fun answerSelfUpdatePrompt(enable: Boolean) {
        store.edit { prefs ->
            prefs[SELF_UPDATE_ASKED] = true
            prefs[SELF_UPDATE_CHECK] = enable
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
            selfUpdateCheck = this[SELF_UPDATE_CHECK] ?: defaults.selfUpdateCheck,
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
        private val SELF_UPDATE_CHECK = booleanPreferencesKey("self_update_check")
        private val PENDING_FROM_CODE = longPreferencesKey("pending_self_update_from_code")
        private val PENDING_FROM_NAME = stringPreferencesKey("pending_self_update_from_name")
        private val PENDING_TO_CODE = longPreferencesKey("pending_self_update_to_code")
        private val PENDING_TO_NAME = stringPreferencesKey("pending_self_update_to_name")
        private val PENDING_AT = longPreferencesKey("pending_self_update_at")
        private val PENDING_SESSION = intPreferencesKey("pending_self_update_session")
        private val PENDING_AUTOMATIC = booleanPreferencesKey("pending_self_update_automatic")
        private val SELF_UPDATE_ASKED = booleanPreferencesKey("self_update_prompt_answered")
        private val SELF_REPO_ID = longPreferencesKey("self_repo_id")
        private val SELF_OWNER_ID = longPreferencesKey("self_repo_owner_id")

        fun create(context: Context): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("settings") })
    }
}
