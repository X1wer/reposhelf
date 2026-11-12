// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.selfupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.x1wer.reposhelf.RepoShelfApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A rendszer a saját csomag cseréje után (`MY_PACKAGE_REPLACED`) az ÚJ verziót indítja el ezzel: a saját frissítés
 * eredménye így akkor is rögzül (előzmények, értesítés), ha a felhasználó nem nyitja meg az appot (C. 14.).
 * Nem exportált; a rendszer saját broadcastja ennek ellenére megérkezik. Bemenetet nem olvas.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as RepoShelfApplication).container
        val result = goAsync()
        container.appScope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MILLIS) { container.selfUpdater.onProcessStart().await() }
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
