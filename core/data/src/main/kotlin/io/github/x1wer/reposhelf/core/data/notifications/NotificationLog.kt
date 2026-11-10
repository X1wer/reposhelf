// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.notifications

import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.NotificationLogEntity
import java.time.Clock

/**
 * Értesítés-deduplikáció (C. 13.: ne legyen ismétlődő értesítés ugyanarról a változatlan állapotról).
 * A kulcs a téma (pl. `update:12`), az állapot-hash a konkrét állapot (pl. a kiadás azonosítója).
 */
class NotificationLog(db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = db.trackedAppDao()

    /** `true`, ha erről az állapotról még nem ment értesítés (és rögzíti); `false`, ha már ment. */
    suspend fun claim(key: String, stateHash: String): Boolean {
        if (dao.notification(key)?.stateHash == stateHash) return false
        dao.upsertNotification(NotificationLogEntity(key.take(MAX_KEY), stateHash.take(MAX_KEY), clock.millis()))
        return true
    }

    /** Az állapot megszűnt (pl. a frissítés települt): a következő változásról ismét mehet értesítés. */
    suspend fun clear(key: String) = dao.deleteNotification(key)

    private companion object {
        const val MAX_KEY = 200
    }
}
