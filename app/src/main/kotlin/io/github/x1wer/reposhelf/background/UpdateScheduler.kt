// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.background

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.x1wer.reposhelf.core.update.CheckFrequency
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import java.util.concurrent.TimeUnit

/**
 * Az automatikus frissítésellenőrzés ütemezése (C. 12.2., docs/ARCHITECTURE.md 12. pont).
 * - Egyedi, periodikus munka; a beállítás változásakor `UPDATE` (nem `REPLACE`), így a futó munka nem szakad meg.
 * - Feltételek: hálózat (opcionálisan csak forgalomkorlát nélküli) és nem alacsony akkumulátor. `requiresDeviceIdle`
 *   szándékosan nincs (sok telefon soha nem kerül „idle” állapotba; az F-Droid #3214 tanulsága).
 * - Az újraindítás utáni megmaradást a WorkManager biztosítja; „csak kézi” módban a munka törlődik.
 * - A pontos időpont nem garantált (Doze, App Standby): a felület az utolsó futás idejét mutatja.
 */
object UpdateScheduler {
    const val WORK_NAME = "reposhelf-update-check"
    private const val BACKOFF_MINUTES = 30L

    fun apply(workManager: WorkManager, settings: GlobalSettings) {
        val hours = settings.checkFrequency.hours
        if (settings.checkFrequency == CheckFrequency.MANUAL || hours == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.checkOnlyUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
