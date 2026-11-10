// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import io.github.x1wer.reposhelf.core.catalog.CatalogParseResult
import io.github.x1wer.reposhelf.core.catalog.CatalogParser
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
}
