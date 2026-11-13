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
        // Az ismeretlen (de szabályos alakú) szolgáltató nem hiba: a bejegyzés csak kimarad (előre-kompatibilitás).
        assertEquals(badEntries.size - 1, parsed.rejected.size)
        assertEquals(1, parsed.skippedUnknownProvider)
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

    // ---- Biztonsági audit: a katalógus aláírt, de a tartalma így is megbízhatatlan ----

    private fun header(serial: String = "7", generated: String = "2026-11-10T00:00:00Z", expires: String = "2027-02-08T00:00:00Z") =
        """"schemaVersion":1,"catalogSerial":$serial,"generatedAt":"$generated","expiresAt":"$expires""""

    private fun invalidHeader(header: String) = assertIs<CatalogParseResult.Invalid>(parser.parse(catalog(entry(), header = header)))

    @Test
    fun header_rejectsAbsurdSerialsAndTimestamps() {
        // CAT-03: az óriási sorszám minden későbbi katalógust „visszagörgetésként” zárna ki.
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(serial = Long.MAX_VALUE.toString())).reason)
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(serial = (CatalogParser.MAX_SERIAL + 1).toString())).reason)
        assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(), header = header(serial = CatalogParser.MAX_SERIAL.toString()))))
        // CAT-10: szélsőséges évszám (később túlcsordulást okozna), és CAT-04: korlátlan érvényesség.
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(expires = "+999999999-01-01T00:00:00Z")).reason)
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(generated = "1970-01-01T00:00:00Z")).reason)
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(expires = "2099-01-01T00:00:00Z")).reason)
        assertEquals(InvalidReason.BAD_HEADER, invalidHeader(header(generated = "2026-11-10T00:00:00Z", expires = "2027-12-16T00:00:00Z")).reason, "400 napnál hosszabb érvényesség")
        assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(), header = header(expires = "2027-12-14T00:00:00Z"))))
    }

    @Test
    fun unknownProvider_isSkipped_andDoesNotCountAsInvalid() {
        // CAT-07: egy később bevezetett szolgáltató bejegyzései a régi kliensben kimaradnak, de nem érvénytelenítik a katalógust.
        fun other(i: Int) = """{"id":"cb$i","name":"App $i","source":{"provider":"codeberg","repository":"owner/app$i"},"category":"system"}"""
        val parsed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(), other(1), other(2), other(3), other(4))))
        assertEquals(listOf("obtainium"), parsed.catalog.apps.map { it.id })
        assertTrue(parsed.rejected.isEmpty())
        assertEquals(4, parsed.skippedUnknownProvider)
        // A formailag is hibás szolgáltatónév viszont hibás bejegyzés.
        val garbage = """{"id":"bad","name":"Bad","source":{"provider":"Git Lab!","repository":"o/a"},"category":"system"}"""
        val mixed = assertIs<CatalogParseResult.Parsed>(parser.parse(catalog(entry(), entry(id = "bb", repo = "o/b"), entry(id = "cc", repo = "o/c"), entry(id = "dd", repo = "o/d"), entry(id = "ee", repo = "o/e"), garbage)))
        assertEquals("bad", mixed.rejected.single().id)
        // Csak ismeretlen szolgáltatójú bejegyzés (vagy üres lista): használható app nélkül a katalógus nem fogadható el.
        assertEquals(InvalidReason.TOO_MANY_INVALID_ENTRIES, assertIs<CatalogParseResult.Invalid>(parser.parse(catalog(other(1), other(2)))).reason)
        assertEquals(InvalidReason.TOO_MANY_INVALID_ENTRIES, assertIs<CatalogParseResult.Invalid>(parser.parse(catalog())).reason)
    }

    @Test
    fun unsafeAssetFilter_andSpoofedNames_rejectTheEntry() {
        fun withFilter(id: String, filter: String) = entry(id = id, repo = "o/$id", extra = "").dropLast(1) + ""","assetFilter":"$filter"}"""
        val good = (1..8).map { entry(id = "ok$it", repo = "o/ok$it") }.toTypedArray()
        val parsed = assertIs<CatalogParseResult.Parsed>(
            parser.parse(
                catalog(
                    *good,
                    withFilter("fine", "^(?!.*fdroid).*\\\\.apk$"),
                    // CAT-02: visszalépéses robbanást adó minta.
                    withFilter("redos", "^(app-?)+release\\\\.apk$"),
                    // CAT-11: irányváltó karakter a névben.
                    entry(id = "spoof", name = "Signal\\u202Egpj", repo = "o/spoof"),
                ),
            ),
        )
        assertEquals(setOf("redos", "spoof"), parsed.rejected.map { it.id }.toSet())
        assertTrue(parsed.rejected.first { it.id == "redos" }.reason.contains("assetFilter"))
        assertEquals("^(?!.*fdroid).*\\.apk$", parsed.catalog.apps.first { it.id == "fine" }.assetFilter)
    }
}
