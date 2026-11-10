// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.ActivityNotFoundException
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RepoShelfTheme {
                RepoShelfApp()
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
}
