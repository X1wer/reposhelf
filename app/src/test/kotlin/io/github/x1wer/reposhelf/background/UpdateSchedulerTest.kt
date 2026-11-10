// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.background

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.x1wer.reposhelf.core.update.CheckFrequency
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Az automatikus ellenőrzés ütemezése (C. 12.2.; B. 5.6.: ütemezés). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun active(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(UpdateScheduler.WORK_NAME).get().filter { !it.state.isFinished }

    @Test
    fun periodicWork_withNetworkAndBatteryConstraints_butNoIdleRequirement() {
        UpdateScheduler.apply(workManager, GlobalSettings(checkFrequency = CheckFrequency.EVERY_12_HOURS, checkOnlyUnmetered = true))
        val info = active().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.HOURS.toMillis(12), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertFalse(info.constraints.requiresDeviceIdle(), "a requiresDeviceIdle sok telefonon soha nem teljesülne")
    }

    @Test
    fun changingTheFrequency_keepsOneWork_manualCancelsIt() {
        UpdateScheduler.apply(workManager, GlobalSettings(checkFrequency = CheckFrequency.DAILY))
        UpdateScheduler.apply(workManager, GlobalSettings(checkFrequency = CheckFrequency.WEEKLY))
        val info = active().single()
        assertEquals(TimeUnit.DAYS.toMillis(7), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        UpdateScheduler.apply(workManager, GlobalSettings(checkFrequency = CheckFrequency.MANUAL))
        assertTrue(active().isEmpty(), "„csak kézi” módban nincs ütemezett munka")
    }
}
