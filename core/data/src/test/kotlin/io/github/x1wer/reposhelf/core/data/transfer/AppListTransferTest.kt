// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.data.catalog.toEntity
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.CatalogStateEntity
import io.github.x1wer.reposhelf.core.data.tracked.AppOrigin
import io.github.x1wer.reposhelf.core.data.tracked.ImportSummary
import io.github.x1wer.reposhelf.core.data.tracked.RequestBudget
import io.github.x1wer.reposhelf.core.data.tracked.TrackedAppsRepository
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceProvider
import io.github.x1wer.reposhelf.core.source.github.GitHubUrlParser
import io.github.x1wer.reposhelf.core.update.AppOverrides
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Import/export (C. 25., B. 7.8.): kör két „készülék” között, szigorú validáció, telepítés és hálózat nélkül. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppListTransferTest {

    /** Minden hívást számol: az import egyetlen hálózati kérést sem indíthat. */
    private class CountingProvider : SourceProvider {
        override val id = ProviderId.GITHUB
        var calls = 0
        override fun parseRepoUrl(input: String): RepoRef? = GitHubUrlParser.parse(input)
        override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> { calls++; return Fetch.NotFound }
        override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> { calls++; return Fetch.NotFound }
        override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> { calls++; return Fetch.NotFound }
        override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> { calls++; return Fetch.NotFound }
        override fun downloadPolicy(maxBytes: Long) = HttpPolicy.of("github.com", maxBodyBytes = maxBytes)
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = MutableClock(Instant.parse("2026-11-12T10:00:00Z"))
    private val dbs = mutableListOf<AppDatabase>()
    private var installedLookups = 0

    private fun device(provider: CountingProvider): Pair<AppDatabase, TrackedAppsRepository> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build().also { dbs += it }
        val repo = TrackedAppsRepository(
            db, ProviderRegistry(listOf(provider)), { installedLookups++; null }, { DeviceProfile(35, listOf("x86_64"), 1) },
            RequestBudget.UNLIMITED, clock,
        )
        return db to repo
    }

    @After
    fun tearDown() = dbs.forEach { it.close() }

    private val aegis = CatalogApp(
        "aegis", "Aegis Authenticator", RepoRef(ProviderId.GITHUB, "beemdevelopment", "Aegis"), ProviderRepoId(65757761), null, emptyList(), null,
        emptyList(), CatalogCategory.SECURITY, null,
    )

    private suspend fun AppDatabase.seedCatalog(vararg apps: CatalogApp) =
        catalogDao().replaceCatalog(CatalogStateEntity(0, 1, 1, 0, Long.MAX_VALUE, "x", "BUNDLED", apps.size, 0, null, null, 0, null, null, null, null), apps.map { it.toEntity() })

    @Test
    fun exportImport_roundTrip_restoresFollowsFavoritesOwnSourcesAndSettings_withoutNetworkOrInstall(): Unit = runBlocking {
        val oldProvider = CountingProvider()
        val (oldDb, old) = device(oldProvider)
        oldDb.seedCatalog(aegis)
        val aegisId = old.followCatalogApp(aegis)
        old.setOverrides(aegisId, AppOverrides(autoDownload = true, releaseDelayDays = 3, includePrereleases = false))
        old.setFavoriteForCatalogApp(aegis, true)

        val bytes = AppListFormat.encode(old.exportList(), clock.instant())
        val text = String(bytes)
        assertTrue("token" !in text && "signer" !in text.lowercase() && "packageName" !in text, "titok és bizalmi adat nincs az exportban")

        val newProvider = CountingProvider()
        val (newDb, fresh) = device(newProvider)
        newDb.seedCatalog(aegis)
        installedLookups = 0
        val decoded = assertIs<AppListDecodeResult.Decoded>(AppListFormat.decode(bytes))
        // Az automatikus letöltés (mint minden automatizmus) importból nem kapcsolódik be; a többi beállítás átjön.
        assertEquals(ImportSummary(added = 1, updated = 0, automationNotImported = 1), fresh.importList(decoded.entries))

        val app = fresh.observeAll().first().single()
        assertEquals(AppOrigin.CATALOG, app.origin)
        assertEquals("aegis", app.catalogId)
        assertTrue(app.isFollowed && app.isFavorite)
        assertEquals(AppOverrides(includePrereleases = false, releaseDelayDays = 3), app.overrides)
        assertEquals(0, newProvider.calls, "az import nem kér le semmit a hálózatról")
        assertEquals(0, installedLookups, "az import nem nyúl a telepítéshez")
        assertNull(app.lastCheckAt)
    }

    @Test
    fun ownSource_andAutoInstallIsNeverEnabledByAnImport(): Unit = runBlocking {
        val file = """
            {"format":"reposhelf-app-list","version":1,"exportedAt":"2026-11-12T10:00:00Z","unknownField":1,"apps":[
              {"source":{"provider":"github","repository":"libre-tube/LibreTube","repositoryId":42},"name":"LibreTube","followed":true,
               "settings":{"autoInstall":true,"notify":false}},
              {"source":{"provider":"github","repository":"evil/Aegis"},"catalogId":"aegis","name":"Fake Aegis","followed":true}
            ]}
        """.trimIndent().toByteArray()
        val (db, repo) = device(CountingProvider())
        db.seedCatalog(aegis)
        val decoded = assertIs<AppListDecodeResult.Decoded>(AppListFormat.decode(file))
        assertEquals(ImportSummary(added = 2, updated = 0, automationNotImported = 1), repo.importList(decoded.entries))
        val apps = repo.observeAll().first().associateBy { it.repo.path }
        val libre = apps.getValue("libre-tube/LibreTube")
        assertEquals(AppOrigin.USER, libre.origin)
        // A stabil azonosítót nem a fájlból vesszük: az első ellenőrzés rögzíti a név alapján (IMP-03).
        assertNull(libre.repoId)
        assertEquals(AppOverrides(notify = false), libre.overrides, "az automatikus telepítés nem kapcsolódott be")
        // A katalógusazonosító csak akkor érvényes, ha a helyi katalógusban ugyanarra a repóra mutat.
        val fake = apps.getValue("evil/Aegis")
        assertEquals(AppOrigin.USER, fake.origin)
        assertNull(fake.catalogId)
        // A megjelenített név a repó neve, nem a fájlban megadott szöveg (IMP-04).
        assertEquals("Aegis", fake.displayName)
    }

    @Test
    fun importNeverRemoves_existingFavoriteBecomesFollowedToo(): Unit = runBlocking {
        val (db, repo) = device(CountingProvider())
        db.seedCatalog(aegis)
        repo.setFavoriteForCatalogApp(aegis, true)
        val entry = AppListEntry(aegis.repo, null, "aegis", "Aegis", followed = true, favorite = false, overrides = null)
        assertEquals(ImportSummary(0, 1, 0), repo.importList(listOf(entry)))
        val app = repo.observeAll().first().single()
        assertTrue(app.isFollowed && app.isFavorite, "a kedvenc megmaradt, a követés bekapcsolt")
    }

    @Test
    fun decode_isStrict() {
        fun decode(text: String) = AppListFormat.decode(text.toByteArray())
        assertEquals(AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED), decode("{nem json"))
        assertEquals(AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.NOT_AN_APP_LIST), decode("""{"format":"other","version":1,"apps":[]}"""))
        assertEquals(AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.UNSUPPORTED_VERSION), decode("""{"format":"reposhelf-app-list","version":2,"apps":[]}"""))
        assertEquals(AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.TOO_LARGE), AppListFormat.decode(ByteArray(AppListFormat.MAX_BYTES + 1)))

        val good = """{"source":{"provider":"github","repository":"o/a"},"followed":true}"""
        val bad = listOf(
            """{"source":{"provider":"gitlab","repository":"o/a"},"followed":true}""",
            """{"source":{"provider":"github","repository":"https://evil.example/o/a"},"followed":true}""",
            """{"source":{"provider":"github","repository":"o/b","repositoryId":"42"},"followed":true}""",
            """{"source":{"provider":"github","repository":"o/c"},"followed":"true"}""",
            """{"source":{"provider":"github","repository":"o/d"},"followed":false,"favorite":false}""",
            """{"source":{"provider":"github","repository":"o/e"},"followed":true,"settings":{"releaseDelayDays":99}}""",
            """{"source":{"provider":"github","repository":"o/f"},"followed":true,"catalogId":"Bad Id"}""",
            """{"source":{"provider":"github","repository":"o/g"},"followed":true,"name":"x\u0007y"}""",
            good,
        )
        val result = assertIs<AppListDecodeResult.Decoded>(decode("""{"format":"reposhelf-app-list","version":1,"apps":[$good,${bad.joinToString(",")}]}"""))
        assertEquals(listOf("o/a"), result.entries.map { it.repo.path })
        assertEquals(bad.size, result.skipped.size, "minden hibás bejegyzés kimarad, okkal")
        assertEquals("duplicate", result.skipped.last().reason)
    }

    // ---- IMP-01: az import nem írja felül a MÁR követett alkalmazások beállításait ----

    @Test
    fun import_neverChangesSettingsOfAppsAlreadyOnTheList(): Unit = runBlocking {
        val (db, repo) = device(CountingProvider())
        db.seedCatalog(aegis)
        val id = repo.followCatalogApp(aegis)
        val mine = AppOverrides(checkEnabled = true, autoInstall = true, releaseDelayDays = 7)
        repo.setOverrides(id, mine)
        val hostile = AppOverrides(checkEnabled = false, notify = false, includePrereleases = true, autoDownload = true, releaseDelayDays = 0)
        val entries = listOf(
            // Ugyanaz a repó útvonal szerint…
            AppListEntry(aegis.repo, null, "aegis", "Aegis", followed = true, favorite = true, overrides = hostile),
        )
        assertEquals(ImportSummary(added = 0, updated = 1, automationNotImported = 0, existingSettingsKept = 1), repo.importList(entries))
        // …és csak a stabil azonosító szerint, megtévesztő útvonallal.
        val decoy = AppListEntry(RepoRef(ProviderId.GITHUB, "decoy", "name"), aegis.repositoryId?.value, null, "x", followed = true, favorite = false, overrides = hostile)
        assertEquals(ImportSummary(0, 1, 0, 1), repo.importList(listOf(decoy)))
        val app = repo.observeAll().first().single()
        assertEquals(mine, app.overrides, "a meglévő beállítások változatlanok")
        assertTrue(app.checkEnabled, "a frissítésellenőrzést egy importfájl nem kapcsolhatja ki")
        assertTrue(app.isFavorite, "a kedvenc bekapcsolódhat")
    }

    @Test
    fun import_neverTurnsOnAutomationForNewApps_butKeepsRestrictiveSettings(): Unit = runBlocking {
        val (_, repo) = device(CountingProvider())
        val entry = AppListEntry(
            RepoRef(ProviderId.GITHUB, "owner", "app"), 42, null, "App", followed = true, favorite = false,
            overrides = AppOverrides(checkEnabled = true, includePrereleases = true, autoDownload = true, autoInstall = true, notify = false, releaseDelayDays = 3),
        )
        assertEquals(ImportSummary(1, 0, 1, 0), repo.importList(listOf(entry)))
        assertEquals(AppOverrides(checkEnabled = true, notify = false, releaseDelayDays = 3), repo.observeAll().first().single().overrides)
        // A kifejezetten KIkapcsolt automatizmus (szigorúbb beállítás) átjön.
        val strict = AppListEntry(
            RepoRef(ProviderId.GITHUB, "owner", "strict"), null, null, "S", followed = true, favorite = false,
            overrides = AppOverrides(autoDownload = false, autoInstall = false, includePrereleases = false),
        )
        assertEquals(ImportSummary(1, 0, 0, 0), repo.importList(listOf(strict)))
        val imported = repo.observeAll().first().first { it.repo.path == "owner/strict" }
        assertEquals(AppOverrides(autoDownload = false, autoInstall = false, includePrereleases = false), imported.overrides)
    }

    // ---- IMP-02: előnézet megerősítéshez, kisebb bejegyzéskorlát ----

    @Test
    fun preview_listsNewOwnSources_withoutWritingAnything(): Unit = runBlocking {
        val provider = CountingProvider()
        val (db, repo) = device(provider)
        db.seedCatalog(aegis)
        val mine = AppListEntry(RepoRef(ProviderId.GITHUB, "mine", "already"), null, null, "x", followed = true, favorite = false, overrides = null)
        repo.importList(listOf(mine))
        val entries = listOf(
            AppListEntry(aegis.repo, null, "aegis", "Aegis", followed = true, favorite = false, overrides = null),
            mine,
            AppListEntry(RepoRef(ProviderId.GITHUB, "stranger", "tool"), null, null, "Tool", followed = true, favorite = false, overrides = null),
            AppListEntry(RepoRef(ProviderId.GITHUB, "evil", "Aegis"), null, "aegis", "Aegis", followed = true, favorite = false, overrides = null),
        )
        val before = repo.observeAll().first().size
        val preview = repo.previewImport(entries)
        assertEquals(1, preview.existing)
        assertEquals(1, preview.newFromCatalog)
        assertEquals(listOf("stranger/tool", "evil/Aegis"), preview.newOwnSources, "a más repóra mutató katalógusazonosító saját forrásnak számít")
        assertEquals(before, repo.observeAll().first().size, "az előnézet semmit nem ír")
        assertEquals(0, provider.calls)
    }

    @Test
    fun decode_rejectsTooManyEntries_deepNesting_andFormatCharactersInNames() {
        fun decode(text: String) = AppListFormat.decode(text.toByteArray())
        val one = { i: Int -> """{"source":{"provider":"github","repository":"o/r$i"},"followed":true}""" }
        val atLimit = (1..AppListFormat.MAX_ENTRIES).joinToString(",", transform = one)
        assertEquals(AppListFormat.MAX_ENTRIES, assertIs<AppListDecodeResult.Decoded>(decode("""{"format":"reposhelf-app-list","version":1,"apps":[$atLimit]}""")).entries.size)
        val overLimit = (1..AppListFormat.MAX_ENTRIES + 1).joinToString(",", transform = one)
        assertEquals(
            AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.TOO_MANY_ENTRIES),
            decode("""{"format":"reposhelf-app-list","version":1,"apps":[$overLimit]}"""),
        )
        // Mélyen egymásba ágyazott tömbök / objektumok: elutasítás kivétel (verem-túlcsordulás) nélkül.
        val malformed = AppListDecodeResult.Invalid(AppListDecodeResult.Invalid.Reason.MALFORMED)
        assertEquals(malformed, decode("""{"format":"reposhelf-app-list","version":1,"apps":""" + "[".repeat(300_000)))
        assertEquals(malformed, decode("""{"format":"reposhelf-app-list","version":1,"apps":""" + """{"a":""".repeat(100_000)))
        // A karakterláncon belüli zárójelek nem számítanak a mélységbe.
        val brackets = "[{".repeat(50)
        val named = """{"format":"reposhelf-app-list","version":1,"apps":[{"source":{"provider":"github","repository":"o/a"},"name":"$brackets","followed":true}]}"""
        assertEquals(1, assertIs<AppListDecodeResult.Decoded>(decode(named)).entries.size)
        assertTrue(AppListFormat.exceedsDepth("[[[[", 3))
        assertTrue(!AppListFormat.exceedsDepth("[\"[[[[[[\", \"\\\"[[[[\"]", 3), "idézőjelen belül, kiléptetett idézőjellel")
        // Irányváltó és nulla szélességű karakter a névben: a bejegyzés kimarad.
        for (hidden in listOf("\\u202E", "\\u200B", "\\u2066")) {
            val spoofed = """{"format":"reposhelf-app-list","version":1,"apps":[{"source":{"provider":"github","repository":"o/a"},"name":"Signal$hidden","followed":true}]}"""
            val result = assertIs<AppListDecodeResult.Decoded>(decode(spoofed))
            assertTrue(result.entries.isEmpty() && result.skipped.single().reason == "name", hidden)
        }
    }
}
