// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

import java.time.Instant

/**
 * Rögzített kategórialista (a katalógus `categories.json`-jával egyező azonosítók).
 * A [searchTerms] a keresésben használt, normalizált (ékezet nélküli, kisbetűs) angol és magyar kifejezések,
 * így például a „jatek” vagy a „games” szó is megtalálja a játékokat.
 */
enum class CatalogCategory(val id: String, val searchTerms: List<String>) {
    COMMUNICATION("communication", listOf("communication", "kommunikacio", "chat", "uzenet")),
    DEVELOPMENT("development", listOf("development", "fejlesztes", "developer", "fejleszto")),
    EDUCATION("education", listOf("education", "oktatas", "tanulas")),
    GAMES("games", listOf("games", "game", "jatek", "jatekok")),
    GRAPHICS("graphics", listOf("graphics", "grafika", "kep", "foto", "photo")),
    INTERNET("internet", listOf("internet", "web", "bongeszo", "browser")),
    MAPS("maps", listOf("maps", "map", "terkep", "navigacio", "navigation")),
    MULTIMEDIA("multimedia", listOf("multimedia", "media", "video", "zene", "music", "audio")),
    PRODUCTIVITY("productivity", listOf("productivity", "produktivitas", "iroda", "office")),
    READING("reading", listOf("reading", "olvasas", "konyv", "book", "hirek", "news")),
    SCIENCE("science", listOf("science", "tudomany", "szamologep", "calculator")),
    SECURITY("security", listOf("security", "biztonsag", "adatvedelem", "privacy")),
    SYSTEM("system", listOf("system", "rendszer", "fajlkezelo", "files")),
    UTILITIES("utilities", listOf("utilities", "eszkozok", "segedprogram", "tools")),
    OTHER("other", listOf("other", "egyeb")),
    ;

    companion object {
        /** Ismeretlen azonosító → [OTHER] (előre-kompatibilitás: egy újabb katalógus új kategóriát is hozhat). */
        fun fromId(id: String?): CatalogCategory = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** Egy érvényesített katalógusbejegyzés (docs/ARCHITECTURE.md 7.1.). */
data class CatalogApp(
    val id: String,
    val name: String,
    val repo: RepoRef,
    val repositoryId: ProviderRepoId?,
    val packageName: String?,
    val signingCertSha256: List<String>,
    val minVersionCode: Long?,
    val keywords: List<String>,
    val category: CatalogCategory,
    val assetFilter: String?,
)

/** Egy elfogadott, érvényes katalógus. */
data class Catalog(
    val schemaVersion: Int,
    val serial: Long,
    val generatedAt: Instant,
    val expiresAt: Instant,
    val apps: List<CatalogApp>,
    /** A letöltött fájl bájtjainak SHA-256-ja (kisbetűs hex), a visszagörgetés-védelemhez. */
    val contentSha256: String,
) {
    fun isExpired(now: Instant): Boolean = now.isAfter(expiresAt)
}
