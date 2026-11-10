// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.x1wer.reposhelf.core.update.CheckFrequency
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A beállítások tartós mentése (C. 12.5–12.6.; B. 5.6.: beállítások mentése). */
class SettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun <T> withStore(file: File, block: suspend (SettingsRepository, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(SettingsRepository(store), store)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun defaults_thenChangesSurviveANewInstance() {
        val file = File(temp.root, "settings.preferences_pb")
        withStore(file) { repo, _ ->
            assertEquals(GlobalSettings(), repo.current())
            assertNull(repo.lastBackgroundRun.first())
            repo.update { it.copy(checkFrequency = CheckFrequency.WEEKLY, autoDownload = true, releaseDelayDays = 3, notifyUpdates = false) }
            repo.recordBackgroundRun(BackgroundRun(Instant.parse("2026-11-10T10:00:00Z"), checked = 4, updates = 1, notChecked = 2))
            repo.markNotificationPermissionAsked()
        }
        // Új példány ugyanazon a fájlon: a beállítások megmaradtak.
        withStore(file) { repo, _ ->
            val settings = repo.current()
            assertEquals(CheckFrequency.WEEKLY, settings.checkFrequency)
            assertTrue(settings.autoDownload)
            assertFalse(settings.autoInstall)
            assertEquals(3, settings.releaseDelayDays)
            assertFalse(settings.notifyUpdates)
            assertEquals(BackgroundRun(Instant.parse("2026-11-10T10:00:00Z"), 4, 1, 2), repo.lastBackgroundRun.first())
            assertTrue(repo.notificationPermissionAsked())
        }
    }

    @Test
    fun unknownOrOutOfRangeValues_fallBackSafely() {
        val file = File(temp.root, "broken.preferences_pb")
        withStore(file) { repo, store ->
            store.edit { it[stringPreferencesKey("check_frequency")] = "EVERY_MINUTE" }
            assertEquals(CheckFrequency.DAILY, repo.current().checkFrequency)
            repo.update { it.copy(releaseDelayDays = 500) }
            assertEquals(30, repo.current().releaseDelayDays)
        }
    }
}
