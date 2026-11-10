// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.work.WorkManager
import io.github.x1wer.reposhelf.background.UpdateScheduler
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RepoShelfApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
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
