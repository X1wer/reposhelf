// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.readme

/**
 * Egy README (vagy kiadási megjegyzés) BIZTONSÁGOS, korlátozott dokumentummodellje (C. 6.1., D-013).
 * Csak ezek az elemek léteznek; nincs HTML, nincs script, nincs WebView. Az URL-ek már feloldottak és szűrtek:
 * a kép csak HTTPS lehet, a link csak `https`, `http` vagy `mailto`.
 */
data class MarkdownDocument(
    val blocks: List<MdBlock>,
    /** Igaz, ha a dokumentum korlátozott módban jelenik meg (túl nagy vagy nem feldolgozható). */
    val restricted: Boolean = false,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdSpan>) : MdBlock
    data class Paragraph(val text: List<MdSpan>) : MdBlock
    data class Image(val image: MdImage) : MdBlock
    data class Code(val code: String, val language: String?) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Rule : MdBlock
}

/** Formázott szövegrész. A [link] már ellenőrzött, abszolút URL (vagy `null`). */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/**
 * Kép. A [url] `null`, ha nem tölthető be biztonságosan (nem HTTPS, ismeretlen séma).
 * [hostedByGitHub]: a GitHub saját tárhelyéről jön (automatikusan betölthető); a külső hosztú kép csak a
 * felhasználó kérésére töltődik be, mert a betöltés felfedi az IP-címet és követésre is használható (C. 31.).
 * [svg]: SVG-t nem renderelünk (aktív tartalom lehet), helyette a helyettesítő szöveg látszik.
 */
data class MdImage(val url: String?, val alt: String, val hostedByGitHub: Boolean, val svg: Boolean)

/** A relatív hivatkozások feloldásához szükséges adatok (GitHub). */
data class LinkContext(val owner: String, val repo: String, val ref: String, val baseDir: String = "")
