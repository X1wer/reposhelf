// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migráció v1 → v2: egy valódi, az exportált 1-es sémából felépített adatbázist nyit meg a jelenlegi kód.
 * A katalógusadatnak meg kell maradnia, az új tábláknak használhatónak kell lenniük. Romboló migráció nincs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private fun createVersion1(file: File) {
        val schema = JSONObject(File("schemas/${AppDatabase::class.java.name}/1.json").readText()).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                entity.optJSONArray("contentSyncTriggers")?.let { triggers -> for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                """INSERT INTO catalog_app (catalogId, name, provider, repoOwner, repoName, repositoryId, packageName,
                   signingCertSha256, minVersionCode, keywords, category, assetFilter, searchName, searchKeywords)
                   VALUES ('aegis', 'Aegis', 'github', 'beemdevelopment', 'Aegis', 6, 'com.beemdevelopment.aegis', '', NULL, '', 'security', NULL, 'aegis', '')""",
            )
            db.version = 1
        }
    }

    @Test
    fun version1_migratesTo2_keepingCatalogData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createVersion1(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.path).build()
        try {
            val app = assertNotNull(db.catalogDao().byId("aegis"))
            assertEquals("com.beemdevelopment.aegis", app.packageName)
            // Az FTS-index a migráció után is működik.
            assertEquals(listOf("aegis"), db.catalogDao().searchFts("aegis*", null, 10).map { it.catalogId })

            val tracked = db.trackedAppDao()
            assertNull(tracked.pin("com.beemdevelopment.aegis"))
            tracked.upsertPin(SignerPinEntity("com.beemdevelopment.aegis", "ab", "FIRST_INSTALL_VIA_US", 1, null))
            assertEquals("ab", tracked.pin("com.beemdevelopment.aegis")?.certsSha256)
            assertTrue(db.openHelper.readableDatabase.version == 2)
        } finally {
            db.close()
            file.delete()
        }
    }
}
