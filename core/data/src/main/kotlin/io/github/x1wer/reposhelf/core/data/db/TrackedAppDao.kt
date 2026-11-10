// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TrackedAppDao {

    @Transaction
    @Query("SELECT * FROM tracked_app ORDER BY displayName COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<TrackedAppWithSettings>>

    @Transaction
    @Query("SELECT * FROM tracked_app WHERE id = :id")
    abstract fun observe(id: Long): Flow<TrackedAppWithSettings?>

    @Transaction
    @Query("SELECT * FROM tracked_app WHERE id = :id")
    abstract suspend fun byId(id: Long): TrackedAppWithSettings?

    @Transaction
    @Query("SELECT * FROM tracked_app WHERE isFollowed = 1 ORDER BY lastCheckAtMillis IS NOT NULL, lastCheckAtMillis")
    abstract suspend fun followedOldestCheckFirst(): List<TrackedAppWithSettings>

    @Query("SELECT * FROM tracked_app WHERE repoKey = :repoKey")
    abstract suspend fun byRepoKey(repoKey: String): TrackedAppEntity?

    @Query("SELECT * FROM tracked_app WHERE provider = :provider AND repoId = :repoId")
    abstract suspend fun byRepoId(provider: String, repoId: Long): TrackedAppEntity?

    @Query("SELECT * FROM tracked_app WHERE catalogId = :catalogId")
    abstract suspend fun byCatalogId(catalogId: String): TrackedAppEntity?

    @Query("SELECT * FROM tracked_app WHERE packageName = :packageName")
    abstract suspend fun byPackageName(packageName: String): List<TrackedAppEntity>

    @Insert
    abstract suspend fun insert(app: TrackedAppEntity): Long

    @Update
    abstract suspend fun update(app: TrackedAppEntity)

    @Query("DELETE FROM tracked_app WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM app_settings_override WHERE trackedAppId = :id")
    abstract suspend fun override(id: Long): AppSettingsOverrideEntity?

    @Upsert
    abstract suspend fun upsertOverride(override: AppSettingsOverrideEntity)

    @Query("SELECT * FROM signer_pin WHERE packageName = :packageName")
    abstract suspend fun pin(packageName: String): SignerPinEntity?

    @Upsert
    abstract suspend fun upsertPin(pin: SignerPinEntity)

    @Query("SELECT * FROM apk_analysis WHERE provider = :provider AND assetId = :assetId")
    abstract suspend fun analysis(provider: String, assetId: Long): ApkAnalysisEntity?

    @Query("SELECT * FROM apk_analysis WHERE provider = :provider AND assetId IN (:assetIds)")
    abstract suspend fun analyses(provider: String, assetIds: List<Long>): List<ApkAnalysisEntity>

    @Upsert
    abstract suspend fun upsertAnalysis(analysis: ApkAnalysisEntity)

    @Query("SELECT * FROM notification_log WHERE dedupKey = :key")
    abstract suspend fun notification(key: String): NotificationLogEntity?

    @Upsert
    abstract suspend fun upsertNotification(entry: NotificationLogEntity)

    @Query("DELETE FROM notification_log WHERE dedupKey = :key")
    abstract suspend fun deleteNotification(key: String)

    /** A repó legutóbbi ELFOGADOTT elemzésének csomagneve (elutasítottból nem tanulunk). */
    @Query("SELECT packageName FROM apk_analysis WHERE repoKey = :repoKey AND verdict = 'OK' AND packageName IS NOT NULL ORDER BY analyzedAtMillis DESC LIMIT 1")
    abstract suspend fun lastAcceptedPackage(repoKey: String): String?
}
