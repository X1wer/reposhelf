// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.data.catalog.RefreshOutcome
import io.github.x1wer.reposhelf.core.data.settings.BackgroundRun
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant

/**
 * Periodikus frissítésellenőrzés (C. 12.2., docs/ARCHITECTURE.md 12. pont). Sorrend:
 * 1. a katalógus feltételes frissítése (értesítés, ha új katalógus érkezett; C. 13.);
 * 2. a követett alkalmazások ellenőrzése egymás után, keretkímélően (megáll, ha az API-keret fogy);
 * 3. alkalmazásonként: értesítés, automatikus letöltés, és ahol engedett, beavatkozás nélküli telepítés.
 *
 * Mindig sikerrel tér vissza (a következő periódusban újra fut): rate limit vagy hálózati hiba nem okoz újrapróbálási
 * lavinát. Leállításkor (időkorlát) a félbemaradt letöltés `.part` fájlja megmarad, és később folytatódik.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as RepoShelfApplication).container
        val global = container.settings.current()
        container.backgroundUpdates.pruneDownloads(File(applicationContext.noBackupFilesDir, "downloads"))

        container.catalogRepository.ensureInitialized()
        when (val outcome = container.catalogRepository.refresh(force = false)) {
            is RefreshOutcome.Updated -> container.notifier.catalogUpdated(outcome.appCount, "catalog:${container.catalogRepository.status.first().serial}")
            else -> Unit
        }

        val network = BackgroundUpdates.networkState(applicationContext)
        container.trackedApps.refreshInstalledStates()
        val summary = container.trackedApps.checkAll { app, check ->
            if (!isStopped) container.backgroundUpdates.onChecked(app, check, global, network)
        }
        container.settings.recordBackgroundRun(BackgroundRun(Instant.now(), summary.checked, summary.updatesAvailable, summary.notChecked))
        return Result.success()
    }
}
