// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.app.Application
import io.github.x1wer.reposhelf.ui.common.AppLocales
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.work.WorkManager
import io.github.x1wer.reposhelf.background.UpdateScheduler
import io.github.x1wer.reposhelf.core.installer.ApkInspectionService
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RepoShelfApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** Android 9–12: a választott nyelv a háttérben készülő szövegekre (értesítések) is vonatkozik (D-132). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocales.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Az APK-elemző segédfolyamatban (D-031) az alkalmazás szolgáltatásai nem indulnak el: ott csak a nem exportált
        // ApkInspectionService fut, adatbázis, ütemezés és hálózat nélkül.
        if (ApkInspectionService.isInspectionProcess()) return
        container = AppContainer(this)
        container.notifier.createChannels()
        // A megszakadt telepítések rendezése és a függő saját frissítés egyeztetése (C. 14.).
        container.selfUpdater.onProcessStart()
        // Ütemezés a beállítások szerint; idempotens, minden indításkor és minden változáskor (ARCH 12.).
        container.appScope.launch {
            container.settings.settings
                .map { GlobalSettings(checkFrequency = it.checkFrequency, checkOnlyUnmetered = it.checkOnlyUnmetered) }
                .distinctUntilChanged()
                .collect { settings ->
                    try {
                        UpdateScheduler.apply(WorkManager.getInstance(this@RepoShelfApplication), settings)
                    } catch (e: IllegalStateException) {
                        // A WorkManager nincs inicializálva (pl. tesztkörnyezet): az ütemezés a következő indításkor pótolódik.
                        Log.w("RepoShelf", "Az ütemezés kimaradt", e)
                    }
                }
        }
    }

    /** A README-képek betöltője a szigorú képkliensünkkel (csak HTTPS, méretkorlát; D-013). SVG-dekóder nincs. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.imageHttpClient })) }
            .build()
}
