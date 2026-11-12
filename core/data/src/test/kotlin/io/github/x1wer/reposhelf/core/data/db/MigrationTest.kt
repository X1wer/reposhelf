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

    private fun createVersion1(file: File) = createVersion(file, 1) { db ->
        db.execSQL(
            """INSERT INTO catalog_app (catalogId, name, provider, repoOwner, repoName, repositoryId, packageName,
               signingCertSha256, minVersionCode, keywords, category, assetFilter, searchName, searchKeywords)
               VALUES ('aegis', 'Aegis', 'github', 'beemdevelopment', 'Aegis', 6, 'com.beemdevelopment.aegis', '', NULL, '', 'security', NULL, 'aegis', '')""",
        )
    }

    /** Valódi, az exportált sémából felépített adatbázis a megadott verzióval (Room nélkül). */
    private fun createVersion(file: File, version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/${AppDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
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
            seed(db)
            db.version = version
        }
    }

    @Test
    fun version2_migratesTo3_keepingTrackedAppsPinsAndOverrides() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 2) { db ->
            db.execSQL(
                """INSERT INTO tracked_app (id, origin, catalogId, provider, repoOwner, repoName, repoKey, repoId, repoOwnerId, packageName,
                   displayName, isFollowed, isFavorite, installedViaUs, everInstalled, addedAtMillis, lastCheckAtMillis, checkStatus,
                   checkConfirmed, lastErrorKind, lastErrorAtMillis, repoState, repoStateDetail, latestReleaseId, latestTag, latestName,
                   latestPublishedAtMillis, latestIsPrerelease, latestHtmlUrl, installedReleaseId, installedReleasePublishedAtMillis,
                   installedReleaseVersionCode)
                   VALUES (7, 'USER', NULL, 'github', 'owner', 'app', 'github:owner/app', 42, 1, 'com.example', 'App', 1, 0, 1, 1, 0, NULL,
                   'UP_TO_DATE', 1, NULL, NULL, 'OK', NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL)""",
            )
            db.execSQL("INSERT INTO app_settings_override (trackedAppId, checkEnabled, includePrereleases) VALUES (7, 0, 1)")
            db.execSQL("INSERT INTO signer_pin (packageName, certsSha256, source, pinnedAtMillis, lastVerifiedAtMillis) VALUES ('com.example', 'aa', 'FIRST_INSTALL_VIA_US', 1, NULL)")
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.path).build()
        try {
            val dao = db.trackedAppDao()
            val row = assertNotNull(dao.byId(7))
            assertEquals("com.example", row.app.packageName)
            assertEquals(false, row.override?.checkEnabled)
            assertEquals(true, row.override?.includePrereleases)
            assertNull(row.override?.autoInstall, "az új oszlop üres = a globális beállítás érvényes")
            assertEquals("aa", dao.pin("com.example")?.certsSha256)
            dao.upsertNotification(NotificationLogEntity("update:7", "release:1", 1))
            assertEquals("release:1", dao.notification("update:7")?.stateHash)
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun version3_migratesTo4_keepingDataAndAddingSafetyTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test-3.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 3) { db ->
            db.execSQL("INSERT INTO signer_pin (packageName, certsSha256, source, pinnedAtMillis, lastVerifiedAtMillis) VALUES ('com.example', 'aa', 'EXISTING_INSTALL', 1, NULL)")
            db.execSQL("INSERT INTO notification_log (dedupKey, stateHash, postedAtMillis) VALUES ('update:1', 'release:2', 3)")
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.path).build()
        try {
            assertEquals("aa", db.trackedAppDao().pin("com.example")?.certsSha256)
            assertEquals("release:2", db.trackedAppDao().notification("update:1")?.stateHash)
            db.safetyDao().insertHistory(UpdateHistoryEntity(trackedAppId = 1, appName = "A", packageName = "com.example", fromVersionName = "1", fromVersionCode = 1, toVersionName = "2", toVersionCode = 2, atMillis = 5, outcome = "SUCCESS", failureCode = null, failureMessage = null, method = "MANUAL"))
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            file.delete()
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
            assertTrue(db.openHelper.readableDatabase.version == 4)
        } finally {
            db.close()
            file.delete()
        }
    }
}
