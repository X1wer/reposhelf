// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Biztonsági figyelmeztetés (C. 20., docs/ARCHITECTURE.md 4. és 10.4. pont). Egy esetből egy sor ([dedupKey] egyedi):
 * az ismétlődés csak a [lastSeenAtMillis]-t frissíti. Szándékosan nincs idegen kulcs a követett sorra: a figyelmeztetés
 * és az előzmény a követés megszüntetése után is megmarad.
 */
@Entity(tableName = "security_warning", indices = [Index(value = ["dedupKey"], unique = true), Index(value = ["trackedAppId"])])
data class SecurityWarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedAppId: Long?,
    val packageName: String?,
    val appName: String,
    /** `WarningCategory` neve. */
    val category: String,
    /** `Severity` neve (INFO / WARNING / CRITICAL). */
    val severity: String,
    val dedupKey: String,
    /** Rövid, nem érzékeny részlet (pl. `owner/name → owner2/name`). */
    val detail: String?,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val acknowledgedAtMillis: Long?,
    val resolvedAtMillis: Long?,
)

/** Frissítési előzmény (C. 15.). Token, teljes URL vagy más érzékeny adat nem kerül bele. */
@Entity(tableName = "update_history", indices = [Index(value = ["atMillis"])])
data class UpdateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedAppId: Long?,
    val appName: String,
    val packageName: String?,
    val fromVersionName: String?,
    val fromVersionCode: Long?,
    val toVersionName: String?,
    val toVersionCode: Long?,
    val atMillis: Long,
    /** SUCCESS / FAILED / CANCELLED_BY_USER / BLOCKED. */
    val outcome: String,
    /** Rövid, gépi ok (pl. `CONFLICT`, `SIGNER_CHANGED`). */
    val failureCode: String?,
    /** Rövid, szűrt szöveg (legfeljebb 200 karakter). */
    val failureMessage: String?,
    /** MANUAL / AUTOMATIC / AUTOMATIC_PROMPTED / SELF_UPDATE. */
    val method: String,
)

@Dao
abstract class SafetyDao {
    @Query("SELECT * FROM security_warning WHERE dedupKey = :key")
    abstract suspend fun warningByKey(key: String): SecurityWarningEntity?

    @Insert
    abstract suspend fun insertWarning(warning: SecurityWarningEntity): Long

    @Update
    abstract suspend fun updateWarning(warning: SecurityWarningEntity)

    @Query("SELECT * FROM security_warning WHERE resolvedAtMillis IS NULL AND acknowledgedAtMillis IS NULL ORDER BY lastSeenAtMillis DESC")
    abstract fun observeOpen(): Flow<List<SecurityWarningEntity>>

    @Query("SELECT * FROM security_warning WHERE trackedAppId = :trackedAppId AND resolvedAtMillis IS NULL ORDER BY lastSeenAtMillis DESC")
    abstract fun observeForApp(trackedAppId: Long): Flow<List<SecurityWarningEntity>>

    @Query("SELECT * FROM security_warning WHERE trackedAppId = :trackedAppId AND resolvedAtMillis IS NULL")
    abstract suspend fun unresolvedForApp(trackedAppId: Long): List<SecurityWarningEntity>

    @Query("SELECT * FROM security_warning WHERE trackedAppId IS NULL AND packageName = :packageName AND resolvedAtMillis IS NULL")
    abstract suspend fun unresolvedForPackage(packageName: String): List<SecurityWarningEntity>

    @Query("SELECT * FROM security_warning WHERE trackedAppId IS NULL AND packageName = :packageName AND resolvedAtMillis IS NULL ORDER BY lastSeenAtMillis DESC")
    abstract fun observeForPackage(packageName: String): Flow<List<SecurityWarningEntity>>

    @Query("UPDATE security_warning SET acknowledgedAtMillis = :at WHERE id = :id")
    abstract suspend fun acknowledge(id: Long, at: Long)

    @Insert
    abstract suspend fun insertHistory(entry: UpdateHistoryEntity): Long

    @Query("SELECT * FROM update_history WHERE trackedAppId IS NULL AND packageName = :packageName ORDER BY atMillis DESC, id DESC LIMIT 1")
    abstract suspend fun latestHistoryForPackage(packageName: String): UpdateHistoryEntity?

    @Query("SELECT * FROM update_history ORDER BY atMillis DESC, id DESC LIMIT :limit")
    abstract fun observeHistory(limit: Int): Flow<List<UpdateHistoryEntity>>

    /** A legrégebbi bejegyzések törlése a felső korlát fölött (fölösleges adat ne gyűljön; C. 15.). */
    @Query("DELETE FROM update_history WHERE id NOT IN (SELECT id FROM update_history ORDER BY atMillis DESC, id DESC LIMIT :keep)")
    abstract suspend fun pruneHistory(keep: Int)
}
