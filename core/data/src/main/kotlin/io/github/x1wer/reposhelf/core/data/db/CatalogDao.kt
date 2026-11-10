// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {

    @Query("SELECT * FROM catalog_state WHERE id = 0")
    abstract suspend fun state(): CatalogStateEntity?

    @Query("SELECT * FROM catalog_state WHERE id = 0")
    abstract fun observeState(): Flow<CatalogStateEntity?>

    @Upsert
    protected abstract suspend fun upsertState(state: CatalogStateEntity)

    @Query("DELETE FROM catalog_app")
    protected abstract suspend fun deleteAllApps()

    @Insert
    protected abstract suspend fun insertApps(apps: List<CatalogAppEntity>)

    /** A teljes katalógus cseréje egyetlen tranzakcióban: hiba esetén az előző katalógus érintetlen marad (C. 3.2.). */
    @Transaction
    open suspend fun replaceCatalog(state: CatalogStateEntity, apps: List<CatalogAppEntity>) {
        deleteAllApps()
        insertApps(apps)
        upsertState(state)
    }

    @Query("UPDATE catalog_state SET lastAttemptAtMillis = :at, lastErrorKind = :kind, lastErrorDetail = :detail WHERE id = 0")
    abstract suspend fun recordFailure(at: Long, kind: String, detail: String?)

    @Query(
        """UPDATE catalog_state SET lastAttemptAtMillis = :at, lastSuccessAtMillis = :at, lastErrorKind = NULL,
           lastErrorDetail = NULL, etag = :etag, lastModified = :lastModified, source = :source WHERE id = 0""",
    )
    abstract suspend fun recordUnchanged(at: Long, etag: String?, lastModified: String?, source: String)

    /** Előtag-keresés az FTS4-indexben (minden token `szo*`, ÉS kapcsolattal). */
    @Query(
        """SELECT catalog_app.* FROM catalog_app
           JOIN catalog_app_fts ON catalog_app.rowid = catalog_app_fts.rowid
           WHERE catalog_app_fts MATCH :match AND (:category IS NULL OR catalog_app.category = :category)
           LIMIT :limit""",
    )
    abstract suspend fun searchFts(match: String, category: String?, limit: Int): List<CatalogAppEntity>

    /** Tartalék a szó belsejében lévő részleges egyezéshez (pl. „pipe” → „NewPipe”). */
    @Query(
        """SELECT * FROM catalog_app
           WHERE (searchName LIKE :pattern OR searchKeywords LIKE :pattern)
           AND (:category IS NULL OR category = :category)
           LIMIT :limit""",
    )
    abstract suspend fun searchInfix(pattern: String, category: String?, limit: Int): List<CatalogAppEntity>

    @Query("SELECT * FROM catalog_app WHERE catalogId = :id")
    abstract suspend fun byId(id: String): CatalogAppEntity?

    @Query("SELECT * FROM catalog_app WHERE (:category IS NULL OR category = :category) ORDER BY name COLLATE NOCASE LIMIT :limit")
    abstract suspend fun browse(category: String?, limit: Int): List<CatalogAppEntity>

    @Query("SELECT category AS category, COUNT(*) AS count FROM catalog_app GROUP BY category")
    abstract fun observeCategoryCounts(): Flow<List<CategoryCountRow>>
}
