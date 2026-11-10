// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import java.text.Normalizer
import java.util.Locale

/**
 * Keresési normalizálás (docs/ARCHITECTURE.md 8. pont): ékezet- és kis/nagybetű-független, eszközfüggetlen.
 * Unicode NFD → az ékezetjelek eltávolítása → kisbetűsítés (`Locale.ROOT`) → minden nem betű/szám karakter szóköz.
 * Így „Térkép”, „TERKEP” és „térkép” ugyanaz, és nem függ a készülék SQLite-verziójától.
 */
object SearchText {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /** NFD-vel nem bontható betűk ASCII-megfelelője. */
    private val SPECIAL = mapOf('ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i")

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS.replace(decomposed, "").lowercase(Locale.ROOT)
        val mapped = buildString(stripped.length) {
            for (c in stripped) append(SPECIAL[c] ?: c.toString())
        }
        return NON_ALPHANUMERIC.replace(mapped, " ").trim()
    }

    fun tokens(input: String): List<String> =
        normalize(input).split(' ').filter { it.isNotEmpty() }.map { it.take(MAX_TOKEN_LENGTH) }.distinct().take(MAX_TOKENS)

    const val MAX_TOKENS = 8
    private const val MAX_TOKEN_LENGTH = 64
}

/** A keresési index egy bejegyzéshez (Room-oszlopok). */
data class SearchIndexText(val name: String, val keywords: String)

object SearchIndex {
    /**
     * A kulcsszavas mezőbe a kulcsszavak, a kategória angol és magyar keresőkifejezései és a repó útvonala kerül
     * (így pl. a „jatek” vagy egy GitHub-felhasználónév is talál).
     */
    fun build(app: CatalogApp): SearchIndexText = SearchIndexText(
        name = SearchText.normalize(app.name),
        keywords = SearchText.normalize(
            (app.keywords + app.category.searchTerms + listOf(app.repo.owner, app.repo.name)).joinToString(" "),
        ),
    )
}

/** Egy normalizált keresés: szótöredékek (ÉS kapcsolattal) és opcionális kategóriaszűrő. */
data class SearchQuery(val tokens: List<String>, val category: CatalogCategory?) {
    val hasText: Boolean get() = tokens.isNotEmpty()

    /**
     * FTS4 `MATCH` kifejezés: minden szótöredékből előtag-keresés lesz, a szavak között implicit ÉS kapcsolattal.
     * A tokenek a normalizálás után csak kisbetűt és számjegyet tartalmaznak, ezért FTS-operátor
     * (`OR`, `NOT`, `"`, `*`, `-`) nem kerülhet beléjük: nincs lekérdezés-injekció.
     */
    fun ftsMatch(): String = tokens.joinToString(" ") { "$it*" }

    /** Igaz, ha minden token részszövegként (szó belsejében is) szerepel a szövegben – ez a részleges egyezés tartaléka. */
    fun matchesAllInfix(index: SearchIndexText): Boolean {
        val haystack = index.name + " " + index.keywords
        return tokens.all { haystack.contains(it) }
    }

    companion object {
        fun of(raw: String, category: CatalogCategory? = null) = SearchQuery(SearchText.tokens(raw), category)
    }
}

/**
 * Találatok rangsorolása: pontos névegyezés > névben szókezdet > névben szórészlet > kulcsszó > egyéb;
 * azonos pontszámnál név szerint ábécérendben.
 */
object SearchRanker {
    fun score(query: SearchQuery, index: SearchIndexText): Int {
        if (!query.hasText) return 0
        var score = 0
        if (index.name == query.tokens.joinToString(" ")) score += 1000
        val nameWords = index.name.split(' ')
        val keywordWords = index.keywords.split(' ')
        for (token in query.tokens) {
            score += when {
                nameWords.firstOrNull()?.startsWith(token) == true -> 120
                nameWords.any { it.startsWith(token) } -> 100
                index.name.contains(token) -> 40
                keywordWords.any { it == token } -> 35
                keywordWords.any { it.startsWith(token) } -> 30
                index.keywords.contains(token) -> 10
                else -> 0
            }
        }
        return score
    }

    fun <T> rank(query: SearchQuery, items: List<T>, indexOf: (T) -> SearchIndexText, nameOf: (T) -> String): List<T> =
        items.map { it to score(query, indexOf(it)) }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it.first) })
            .map { it.first }
}
