// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.x1wer.reposhelf.background.Notifier
import io.github.x1wer.reposhelf.background.UpdateCheckWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.ui.RepoShelfApp
import io.github.x1wer.reposhelf.ui.theme.RepoShelfTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Értesítésből kért megnyitás: a követett alkalmazás belső azonosítója (validálva). */
    private val openTracked = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleOpenIntent(intent)
        setContent {
            RepoShelfTheme {
                val request by openTracked.collectAsState()
                RepoShelfApp(openTrackedRequest = request, onOpenHandled = { openTracked.value = null })
            }
        }
        // A PackageInstaller jóváhagyási felületét csak előtérben lévő activity indíthatja (docs/ARCHITECTURE.md 11.1.).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEvents.pendingConfirmation.filterNotNull().collect { event ->
                    InstallEvents.consumePendingConfirmation(event)
                    try {
                        startActivity(event.intent)
                    } catch (_: ActivityNotFoundException) {
                        // A rendszer saját felülete hiányzik: a telepítés nem folytatható; a session időtúllépéssel lezárul.
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Csak a saját értesítéseink belső azonosítója fogadható el (pozitív szám); minden más bemenet figyelmen kívül marad. */
    private fun handleOpenIntent(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_OPEN_TRACKED, -1L) ?: -1L
        if (id > 0) openTracked.value = id
        // Csak debug buildben: a háttérellenőrzés azonnali futtatása emulátoros teszthez (adb), mert a WorkManager a
        // periodikus munkát az esedékesség előtt nem futtatja. Kiadott buildben ez az ág nem létezik (BuildConfig.DEBUG).
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DEBUG_RUN_BACKGROUND_CHECK, false) == true) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "reposhelf-debug-check",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>().build(),
            )
        }
    }

    private companion object {
        const val EXTRA_DEBUG_RUN_BACKGROUND_CHECK = "io.github.x1wer.reposhelf.debug.RUN_BACKGROUND_CHECK"
    }
}
