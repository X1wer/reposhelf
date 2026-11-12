// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import io.github.x1wer.reposhelf.core.catalog.CatalogParseResult
import io.github.x1wer.reposhelf.core.catalog.CatalogParser
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureCheck
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import org.junit.Test
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Az APK-ba csomagolt kezdőkatalógus érvényes, hibás bejegyzés nélküli, és megfelel a minimális sorszámnak. */
class BundledCatalogTest {
    @Test
    fun bundledCatalog_isValid() {
        val file = File("src/main/assets/catalog/catalog-v1.json")
        assertTrue(file.isFile, "Hiányzik a beépített katalógus: ${file.absolutePath}")
        val parsed = assertIs<CatalogParseResult.Parsed>(CatalogParser().parse(file.readBytes()))
        assertTrue(parsed.rejected.isEmpty(), "Hibás bejegyzések: ${parsed.rejected}")
        assertTrue(parsed.catalog.apps.size in 5..10_000)
        assertTrue(parsed.catalog.serial >= BuildConfig.MIN_CATALOG_SERIAL)
        assertTrue(parsed.catalog.apps.all { it.repositoryId != null }, "Minden példabejegyzésnek van repositoryId-je")
    }

    /** A beépített katalógus aláírása a buildbe fordított kulccsal érvényes, és bájtra egyezik a katalógusrepóéval. */
    @Test
    fun bundledCatalog_isSignedWithATrustedKey_andMatchesTheCatalogRepository() {
        val catalog = File("src/main/assets/catalog/catalog-v1.json").readBytes()
        val signature = File("src/main/assets/catalog/catalog-v1.json.sig").readBytes()
        val verifier = CatalogSignatureVerifier(CatalogSignatureVerifier.parseKeys(BuildConfig.CATALOG_PUBLIC_KEYS))
        assertIs<CatalogSignatureCheck.Valid>(verifier.verify(catalog, signature))
        val upstream = File("../../catalog/dist/v1")
        if (upstream.isDirectory) {
            assertTrue(File(upstream, "catalog.json").readBytes().contentEquals(catalog), "a beépített katalógus elavult")
            assertTrue(File(upstream, "catalog.json.sig").readBytes().contentEquals(signature), "a beépített aláírás elavult")
        }
    }
}
