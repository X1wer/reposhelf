// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import io.github.x1wer.reposhelf.core.model.Catalog
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogAcceptanceAndSearchTest {

    private fun catalog(serial: Long, sha: String) = Catalog(
        schemaVersion = 1,
        serial = serial,
        generatedAt = Instant.parse("2026-11-10T00:00:00Z"),
        expiresAt = Instant.parse("2027-02-08T00:00:00Z"),
        apps = emptyList(),
        contentSha256 = sha,
    )

    @Test
    fun acceptance_rules() {
        val current = AcceptedCatalogInfo(serial = 5, contentSha256 = "aaa")
        assertEquals(CatalogDecision.Accept, CatalogAcceptance.decide(null, catalog(1, "x"), minimumSerial = 1))
        assertEquals(CatalogDecision.Accept, CatalogAcceptance.decide(current, catalog(6, "bbb"), minimumSerial = 1))
        assertEquals(CatalogDecision.NoChange, CatalogAcceptance.decide(current, catalog(5, "aaa"), minimumSerial = 1))
        assertEquals(CatalogDecision.RejectSerialReuse(5), CatalogAcceptance.decide(current, catalog(5, "bbb"), minimumSerial = 1))
        assertEquals(CatalogDecision.RejectRollback(5, 4), CatalogAcceptance.decide(current, catalog(4, "ccc"), minimumSerial = 1))
        assertEquals(CatalogDecision.RejectBelowMinimum(3, 2), CatalogAcceptance.decide(null, catalog(2, "ddd"), minimumSerial = 3))
    }

    @Test
    fun normalize_removesDiacriticsAndCase() {
        assertEquals("arvizturo tukorfurogep", SearchText.normalize("ÁRVÍZTŰRŐ tükörfúrógép"))
        assertEquals("terkep", SearchText.normalize("Térkép"))
        assertEquals("strasse oeuvre lodz", SearchText.normalize("Straße œuvre Łódź"))
        assertEquals("new pipe 2 0", SearchText.normalize("  New-Pipe_2.0!  "))
        assertEquals("", SearchText.normalize("!!!"))
    }

    @Test
    fun tokens_areDistinctAndLimited() {
        assertEquals(listOf("video", "player"), SearchText.tokens("Videó  PLAYER videó"))
        assertEquals(SearchText.MAX_TOKENS, SearchText.tokens((1..20).joinToString(" ") { "t$it" }).size)
    }

    @Test
    fun ftsMatch_cannotContainOperators() {
        val query = SearchQuery.of("""foo" OR bar* NOT "baz" -qux NEAR(x)""")
        val match = query.ftsMatch()
        assertEquals("foo* or* bar* not* baz* qux* near* x*", match)
        assertFalse(match.contains('"') || match.contains('-') || match.contains('('))
    }

    private fun app(name: String, keywords: List<String> = emptyList(), category: CatalogCategory = CatalogCategory.OTHER) = CatalogApp(
        id = SearchText.normalize(name).replace(' ', '-').ifEmpty { "x" },
        name = name,
        repo = RepoRef(ProviderId.GITHUB, "owner", name.replace(' ', '-')),
        repositoryId = null,
        packageName = null,
        signingCertSha256 = emptyList(),
        minVersionCode = null,
        keywords = keywords,
        category = category,
        assetFilter = null,
    )

    @Test
    fun infixMatch_supportsPartialWordsAndMultipleTokens() {
        val newPipe = SearchIndex.build(app("NewPipe", listOf("youtube", "videó")))
        assertTrue(SearchQuery.of("pipe").matchesAllInfix(newPipe), "szó belsejében is talál")
        assertTrue(SearchQuery.of("VIDEO new").matchesAllInfix(newPipe), "több kulcsszó, ékezet nélkül")
        assertFalse(SearchQuery.of("video zene").matchesAllInfix(newPipe), "minden tokennek egyeznie kell")
        val game = SearchIndex.build(app("Chess", category = CatalogCategory.GAMES))
        assertTrue(SearchQuery.of("játék").matchesAllInfix(game), "kategória magyar neve is talál")
    }

    @Test
    fun ranking_prefersNameMatches() {
        val items = listOf(
            app("Player Tools", listOf("music")),
            app("Music Player", listOf("audio")),
            app("Music", listOf("player")),
            app("Audio Recorder", listOf("music player")),
        )
        val ranked = SearchRanker.rank(SearchQuery.of("music"), items, SearchIndex::build) { it.name }
        assertEquals(listOf("Music", "Music Player", "Audio Recorder", "Player Tools"), ranked.map { it.name })
    }
}
