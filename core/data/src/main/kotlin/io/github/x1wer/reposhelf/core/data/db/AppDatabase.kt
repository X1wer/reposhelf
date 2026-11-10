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
 */
@Database(
    entities = [
        CatalogStateEntity::class, CatalogAppEntity::class, CatalogAppFts::class,
        TrackedAppEntity::class, AppSettingsOverrideEntity::class, SignerPinEntity::class, ApkAnalysisEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun trackedAppDao(): TrackedAppDao

    companion object {
        const val NAME = "reposhelf.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
