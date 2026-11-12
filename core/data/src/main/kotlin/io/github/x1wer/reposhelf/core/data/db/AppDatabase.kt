// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Az alkalmazás helyi adatbázisa (docs/ARCHITECTURE.md 4. pont).
 * A sémák exportálva vannak (`core/data/schemas`). Minden verzióváltáshoz migráció és migrációs teszt tartozik;
 * romboló (destructive) migráció nincs, mert a követett appok és a rögzített aláírók nem veszhetnek el.
 *
 * Verziók:
 * - 1: katalógus és keresés (2. munkamenet);
 * - 2: követett alkalmazások, alkalmazásonkénti felülírások, rögzített aláírók, APK-elemzések (4. munkamenet).
 *   Csak új táblák, ezért automatikus migráció.
 * - 3: alkalmazásonkénti felülírások bővítése (letöltés, telepítés, értesítés, késleltetés) és értesítési napló
 *   (5. munkamenet). Új, nullázható oszlopok és új tábla: automatikus migráció.
 * - 4: biztonsági figyelmeztetések és frissítési előzmények (6. munkamenet). Csak új táblák: automatikus migráció.
 * - 5: az adatlap tartós (offline) gyorsítótára (7. munkamenet). Csak új tábla: automatikus migráció.
 */
@Database(
    entities = [
        CatalogStateEntity::class, CatalogAppEntity::class, CatalogAppFts::class,
        TrackedAppEntity::class, AppSettingsOverrideEntity::class, SignerPinEntity::class, ApkAnalysisEntity::class,
        NotificationLogEntity::class, SecurityWarningEntity::class, UpdateHistoryEntity::class, DetailsCacheEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun trackedAppDao(): TrackedAppDao
    abstract fun safetyDao(): SafetyDao
    abstract fun detailsCacheDao(): DetailsCacheDao

    companion object {
        const val NAME = "reposhelf.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
