// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/** Az utolsó ÉRVÉNYES katalógus állapota (egyetlen sor, id = 0). docs/ARCHITECTURE.md 4. pont. */
@Entity(tableName = "catalog_state")
data class CatalogStateEntity(
    @PrimaryKey val id: Int = 0,
    val schemaVersion: Int,
    val catalogSerial: Long,
    val generatedAtMillis: Long,
    val expiresAtMillis: Long,
    val contentSha256: String,
    /** `BUNDLED` (az APK-ba csomagolt kezdőkatalógus) vagy `REMOTE` (letöltött). */
    val source: String,
    val appCount: Int,
    val rejectedCount: Int,
    val etag: String?,
    val lastModified: String?,
    val acceptedAtMillis: Long,
    val lastAttemptAtMillis: Long?,
    val lastSuccessAtMillis: Long?,
    val lastErrorKind: String?,
    val lastErrorDetail: String?,
)

/** Egy katalógusbejegyzés. A `search*` oszlopok normalizált (ékezet nélküli, kisbetűs) keresőszövegek. */
@Entity(tableName = "catalog_app")
data class CatalogAppEntity(
    @PrimaryKey val catalogId: String,
    val name: String,
    val provider: String,
    val repoOwner: String,
    val repoName: String,
    val repositoryId: Long?,
    val packageName: String?,
    /** Vesszővel elválasztott, kisbetűs hex ujjlenyomatok. */
    val signingCertSha256: String,
    val minVersionCode: Long?,
    /** Kulcsszavak, a [KEYWORD_SEPARATOR] karakterrel elválasztva. */
    val keywords: String,
    val category: String,
    val assetFilter: String?,
    val searchName: String,
    val searchKeywords: String,
) {
    companion object {
        const val KEYWORD_SEPARATOR = '\u001F'
    }
}

/** FTS4-index a normalizált keresőszövegekre (external content: a `catalog_app` táblára mutat). */
@Fts4(contentEntity = CatalogAppEntity::class)
@Entity(tableName = "catalog_app_fts")
data class CatalogAppFts(
    @ColumnInfo(name = "searchName") val searchName: String,
    @ColumnInfo(name = "searchKeywords") val searchKeywords: String,
)

data class CategoryCountRow(val category: String, val count: Int)
