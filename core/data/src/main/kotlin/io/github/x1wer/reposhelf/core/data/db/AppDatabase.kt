// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Az alkalmazás helyi adatbázisa (docs/ARCHITECTURE.md 4. pont).
 * A sémák exportálva vannak (`core/data/schemas`). A későbbi verziókhoz migráció és migrációs teszt tartozik;
 * romboló (destructive) migráció nincs, mert a követett appok és a rögzített aláírók nem veszhetnek el.
 */
@Database(
    entities = [CatalogStateEntity::class, CatalogAppEntity::class, CatalogAppFts::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        const val NAME = "reposhelf.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
