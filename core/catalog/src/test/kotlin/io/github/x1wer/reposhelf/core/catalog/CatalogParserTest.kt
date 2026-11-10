// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CatalogParserTest {

    private val parser = CatalogParser()

    private fun entry(
        id: String = "obtainium",
        name: String = "Obtainium",
        repo: String = "ImranR98/Obtainium",
        extra: String = "",
        category: String = "system",
    ) = """{"id":"$id","name":"$name","source":{"provider":"github","repository":"$repo"$extra},"keywords":["update","frissítés"],"category":"$category"}"""

    private fun catalog(vararg entries: String, header: String = """"schemaVersion":1,"catalogSerial":7,"generatedAt":"2026-11-10T00:00:00Z","expiresAt":"2027-02-08T00:00:00Z"""") =
        """{$header,"apps":[${entries.joinToString(",")}]}""".toByteArray()

    @Test
    fun validCatalog_isParsedWithAllFields() {
        val bytes = catalog(
            entry(extra = ""","repositoryId":523534328"""),
            """{"id":"newpipe","name":"NewPipe","source":{"provider":"github","repository":"TeamNewPipe/NewPipe"},
               "packageName":"org.schabi.newpipe","signingCertSha256":["${"a".repeat(64)}"],"minVersionCode":990,
               "keywords":["youtube"],"category":"multimedia","assetFilter":"^NewPipe_v.*\\.apk$","futureField":true}""",
        )
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(bytes))
        val catalog = parsed.catalog
        assertEquals(1, catalog.schemaVersion)
        assertEquals(7, catalog.serial)
        assertEquals(Instant.parse("2027-02-08T00:00:00Z"), catalog.expiresAt)
        assertEquals(2, catalog.apps.size)
        assertTrue(parsed.rejected.isEmpty())
        val obtainium = catalog.apps[0]
        assertEquals(ProviderRepoId(523534328), obtainium.repositoryId)
        assertEquals(CatalogCategory.SYSTEM, obtainium.category)
        assertEquals(listOf("update", "frissítés"), obtainium.keywords)
        val newpipe = catalog.apps[1]
        assertEquals("org.schabi.newpipe", newpipe.packageName)
        assertEquals(990L, newpipe.minVersionCode)
        assertEquals(64, catalog.contentSha256.length)
    }

    @Test
    fun malformedJson_isInvalid() {
        assertEquals(CatalogParseResult.Invalid(InvalidReason.MALFORMED_JSON), parser.parse("{not json".toByteArray()))
        assertEquals(CatalogParseResult.Invalid(InvalidReason.MALFORMED_JSON), parser.parse("[1,2,3]".toByteArray()))
        assertEquals(CatalogParseResult.Invalid(InvalidReason.MALFORMED_JSON), parser.parse(byteArrayOf()))
    }

    @Test
    fun newerSchemaVersion_isReportedAsUnsupported() {
        val bytes = """{"schemaVersion":2,"somethingNew":[]}""".toByteArray()
        assertEquals(CatalogParseResult.UnsupportedSchema(2), parser.parse(bytes))
    }

    @Test
    fun missingOrBadHeader_isInvalid() {
        val noSerial = catalog(entry(), header = """"schemaVersion":1,"generatedAt":"2026-11-10T00:00:00Z","expiresAt":"2027-01-01T00:00:00Z"""")
        assertEquals(CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "catalogSerial"), parser.parse(noSerial))

        val expiredBeforeGenerated = catalog(entry(), header = """"schemaVersion":1,"catalogSerial":1,"generatedAt":"2026-11-10T00:00:00Z","expiresAt":"2026-11-09T00:00:00Z"""")
        assertEquals(CatalogParseResult.Invalid(InvalidReason.BAD_HEADER, "expiresAt"), parser.parse(expiredBeforeGenerated))

        val badDate = catalog(entry(), header = """"schemaVersion":1,"catalogSerial":1,"generatedAt":"tegnap","expiresAt":"2027-01-01T00:00:00Z"""")
        assertEquals(CatalogParseResult.Invalid(InvalidReason.MISSING_FIELD, "generatedAt"), parser.parse(badDate))

        val zeroSchema = """{"schemaVersion":0}""".toByteArray()
        assertEquals(CatalogParseResult.Invalid(InvalidReason.BAD_HEADER, "schemaVersion"), parser.parse(zeroSchema))
    }

    @Test
    fun invalidEntries_areSkippedIndividually() {
        val good = (1..9).map { entry(id = "app-$it", name = "App $it", repo = "owner$it/repo$it") }
        val bad = entry(id = "Bad ID!", repo = "x/y")
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(*(good + bad).toTypedArray())))
        assertEquals(9, parsed.catalog.apps.size)
        assertEquals(1, parsed.rejected.size)
        assertEquals(9, parsed.rejected[0].index)
        assertEquals("érvénytelen azonosító", parsed.rejected[0].reason)
    }

    @Test
    fun eachValidationRule_rejectsTheEntry() {
        // 40 jó + 7 hibás: a hibás arány a 20%-os küszöb alatt marad.
        val base = (1..40).map { entry(id = "ok-$it", name = "Ok $it", repo = "ok$it/repo") }
        val badEntries = listOf(
            entry(id = "path-traversal", repo = "../etc"),
            entry(id = "provider", repo = "a/b").replace("\"github\"", "\"evilhub\""),
            entry(id = "repo-id", repo = "c/d", extra = ""","repositoryId":-5"""),
            """{"id":"pkg","name":"P","source":{"provider":"github","repository":"e/f"},"packageName":"not a package","category":"other"}""",
            """{"id":"cert","name":"C","source":{"provider":"github","repository":"g/h"},"signingCertSha256":["ABCDEF"],"category":"other"}""",
            """{"id":"regex","name":"R","source":{"provider":"github","repository":"i/j"},"assetFilter":"([unclosed","category":"other"}""",
            """{"id":"noname","source":{"provider":"github","repository":"k/l"},"category":"other"}""",
        )
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(*(base + badEntries).toTypedArray())))
        assertEquals(40, parsed.catalog.apps.size)
        assertEquals(badEntries.size, parsed.rejected.size)
    }

    @Test
    fun duplicateIdsAndRepos_keepOnlyTheFirst() {
        val entries = (1..10).map { entry(id = "app-$it", name = "App $it", repo = "owner$it/repo$it") } +
            entry(id = "app-1", name = "Dup id", repo = "zzz/zzz") +
            entry(id = "app-dup-repo", name = "Dup repo", repo = "OWNER1/REPO1")
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(*entries.toTypedArray())))
        assertEquals(10, parsed.catalog.apps.size)
        assertEquals(listOf("duplikált azonosító", "duplikált repó: OWNER1/REPO1"), parsed.rejected.map { it.reason })
    }

    @Test
    fun tooManyInvalidEntries_rejectsWholeCatalog() {
        val entries = listOf(entry(id = "good", repo = "a/b"), entry(id = "BAD1"), entry(id = "BAD2"))
        val result = assertIs<CatalogParseResult.Invalid>(parser.parse(catalog(*entries.toTypedArray())))
        assertEquals(InvalidReason.TOO_MANY_INVALID_ENTRIES, result.reason)
    }

    @Test
    fun unknownCategory_fallsBackToOther() {
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(category = "quantum-computing"))))
        assertEquals(CatalogCategory.OTHER, parsed.catalog.apps.single().category)
    }

    @Test
    fun tooManyEntries_isRejectedBeforeValidation() {
        val small = CatalogParser(maxApps = 2)
        val entries = (1..3).map { entry(id = "app-$it", repo = "o$it/r") }
        assertEquals(InvalidReason.TOO_MANY_ENTRIES, assertIs<CatalogParseResult.Invalid>(small.parse(catalog(*entries.toTypedArray()))).reason)
    }

    @Test
    fun contentHash_changesWithContent() {
        val a = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry())))
        val b = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(name = "Obtainium 2"))))
        assertNotEquals(a.catalog.contentSha256, b.catalog.contentSha256)
    }
}
