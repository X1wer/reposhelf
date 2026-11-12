// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Az adatlap tartós gyorsítótára (C. 24.: korábban gyorsítótárazott adatlapok offline; docs/ARCHITECTURE.md 4.).
 * Egy sor egy lekérés eredménye (`releases:` / `repo:` / `readme:` + repó-kulcs), JSON-ként, a feltételes kéréshez
 * szükséges validátorokkal. Titok nincs benne; a legrégebbi sorok a felső korlát fölött törlődnek.
 */
@Entity(tableName = "details_cache", indices = [Index(value = ["fetchedAtMillis"])])
data class DetailsCacheEntity(
    @PrimaryKey val key: String,
    val payload: String,
    val etag: String?,
    val lastModified: String?,
    val fetchedAtMillis: Long,
)

@Dao
abstract class DetailsCacheDao {
    @Query("SELECT * FROM details_cache WHERE `key` = :key")
    abstract suspend fun get(key: String): DetailsCacheEntity?

    @Upsert
    abstract suspend fun upsert(entry: DetailsCacheEntity)

    @Query("DELETE FROM details_cache WHERE `key` NOT IN (SELECT `key` FROM details_cache ORDER BY fetchedAtMillis DESC LIMIT :keep)")
    abstract suspend fun prune(keep: Int)
}
