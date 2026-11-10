// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.notifications

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Értesítés-deduplikáció (C. 13.; B. 5.6.). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationLogTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val log = NotificationLog(db)

    @After
    fun tearDown() = db.close()

    @Test
    fun sameStateIsNotifiedOnce_newStateAgain_clearedStateAgain(): Unit = runBlocking {
        assertTrue(log.claim("update:1", "release:10"))
        assertFalse(log.claim("update:1", "release:10"), "változatlan állapotról nincs újabb értesítés")
        assertTrue(log.claim("update:2", "release:10"), "más alkalmazás külön kulcs")
        assertTrue(log.claim("update:1", "release:11"), "új kiadásról ismét")
        log.clear("update:1")
        assertTrue(log.claim("update:1", "release:11"), "a telepítés után ugyanarról az állapotról ismét mehet")
    }
}
