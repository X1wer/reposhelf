// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.content.Context
import android.os.Build

/**
 * A saját PackageInstaller-sessionjeink állapota a megszakadt folyamatok kezeléséhez (C. 14., docs/ARCHITECTURE.md 14.):
 * - [exists]: egy korábban commitolt session létezik-e még (pl. jóváhagyásra vár), vagy már lezárult;
 * - [abandonOrphaned]: egy korábbi folyamatból maradt, commit nélküli session elhagyása, hogy a folyamat tisztán
 *   újrakezdhető legyen, és a félig írt tartalom ne foglaljon helyet.
 */
class InstallSessions(private val context: Context) {

    fun exists(sessionId: Int): Boolean =
        runCatching { context.packageManager.packageInstaller.getSessionInfo(sessionId) != null }.getOrDefault(false)

    /**
     * A commit nélküli sessionök elhagyása, amelyek legalább [idleMillis] óta nem változtak (a most írt session
     * érintetlen). A commitolt sessionök (jóváhagyásra várók) megmaradnak. Android 9-en (API 28) a session commit-állapota
     * nem kérdezhető le, ezért ott nem nyúlunk hozzájuk: a rendszer a régi sessionöket magától lezárja.
     * Visszaadja az elhagyott sessionök számát.
     */
    fun abandonOrphaned(nowMillis: Long = System.currentTimeMillis(), idleMillis: Long = IDLE_MILLIS): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val installer = context.packageManager.packageInstaller
        var abandoned = 0
        for (info in runCatching { installer.mySessions }.getOrDefault(emptyList())) {
            if (info.isCommitted || nowMillis - info.updatedMillis < idleMillis) continue
            if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) abandoned++
        }
        return abandoned
    }

    private companion object {
        const val IDLE_MILLIS = 10 * 60 * 1000L
    }
}
