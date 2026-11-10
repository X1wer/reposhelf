// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.readme

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown → [MarkdownDocument] (C. 6.1., D-013).
 *
 * Biztonsági szabályok:
 * - a HTML nem renderelődik: a `<script>`/`<style>` tartalma eldobódik, a többi tag eltávolításra kerül;
 *   csak az `<img src>` képek maradnak meg (ugyanazzal a szűréssel, mint a Markdown-képek);
 * - a linkek csak `https`, `http` vagy `mailto` sémájúak lehetnek, a képek csak `https`-ek;
 * - a relatív hivatkozások a repóra oldódnak fel (kép: raw.githubusercontent.com, link: github.com/…/blob/…);
 * - méret- és mélységkorlátok; ha a dokumentum túl nagy vagy nem dolgozható fel, korlátozott (csak szöveg) mód.
 */
object ReadmeParser {
    const val MAX_INPUT_CHARS = 512 * 1024
    private const val MAX_BLOCKS = 3000
    private const val MAX_DEPTH = 8
    private const val MAX_CODE_CHARS = 20_000
    private const val RESTRICTED_CHARS = 20_000

    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val SCRIPT_OR_STYLE = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR_SRC = Regex("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val ATTR_ALT = Regex("\\balt\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val BR_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]*>")
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")
    private val MAILTO = Regex("^mailto:[^\\s<>\"']{3,254}$", RegexOption.IGNORE_CASE)

    fun parse(markdown: String, context: LinkContext?): MarkdownDocument {
        if (markdown.length > MAX_INPUT_CHARS) return restricted(markdown)
        return try {
            val builder = Builder(context)
            val blocks = builder.blocks(parser.parse(markdown), depth = 0)
            if (builder.blockCount > MAX_BLOCKS) restricted(markdown) else MarkdownDocument(blocks)
        } catch (_: RuntimeException) {
            restricted(markdown)
        } catch (_: StackOverflowError) {
            restricted(markdown)
        }
    }

    /** Korlátozott mód: csak a (HTML-től megtisztított) szöveg, linkek és képek nélkül. */
    private fun restricted(markdown: String): MarkdownDocument {
        val text = stripHtml(markdown.take(RESTRICTED_CHARS))
        return MarkdownDocument(text.split(Regex("\n{2,}")).filter { it.isNotBlank() }.map { MdBlock.Paragraph(listOf(MdSpan(it.trim()))) }, restricted = true)
    }

    internal fun stripHtml(html: String): String {
        val noScript = SCRIPT_OR_STYLE.replace(COMMENT.replace(html, ""), "")
        val withBreaks = BR_TAG.replace(noScript, "\n")
        return decodeEntities(ANY_TAG.replace(withBreaks, "")).replace(WHITESPACE, " ").trim()
    }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private class Builder(private val context: LinkContext?) {
        var blockCount = 0

        fun blocks(parent: Node, depth: Int): List<MdBlock> {
            val result = mutableListOf<MdBlock>()
            var child = parent.firstChild
            while (child != null) {
                blockCount++
                if (blockCount > MAX_BLOCKS) return result
                block(child, depth)?.let { result += it }
                child = child.next
            }
            return result
        }

        private fun block(node: Node, depth: Int): List<MdBlock>? {
            if (depth > MAX_DEPTH) return listOf(MdBlock.Paragraph(listOf(MdSpan(plainText(node)))))
            return when (node) {
                is Heading -> listOf(MdBlock.Heading(node.level.coerceIn(1, 6), spans(node)))
                is Paragraph -> paragraph(node)
                is FencedCodeBlock -> listOf(MdBlock.Code(node.literal.orEmpty().take(MAX_CODE_CHARS).trimEnd(), node.info?.takeIf { it.isNotBlank() }?.take(20)))
                is IndentedCodeBlock -> listOf(MdBlock.Code(node.literal.orEmpty().take(MAX_CODE_CHARS).trimEnd(), null))
                is BlockQuote -> listOf(MdBlock.Quote(blocks(node, depth + 1)))
                is BulletList -> listOf(MdBlock.ListBlock(false, 1, items(node, depth)))
                is OrderedList -> listOf(MdBlock.ListBlock(true, node.markerStartNumber ?: 1, items(node, depth)))
                is ThematicBreak -> listOf(MdBlock.Rule)
                is HtmlBlock -> html(node.literal.orEmpty())
                is TableBlock -> listOf(table(node))
                else -> plainText(node).takeIf { it.isNotBlank() }?.let { listOf(MdBlock.Paragraph(listOf(MdSpan(it)))) }
            }?.takeIf { it.isNotEmpty() }
        }

        private fun items(list: Node, depth: Int): List<List<MdBlock>> {
            val items = mutableListOf<List<MdBlock>>()
            var item = list.firstChild
            while (item != null) {
                if (item is ListItem) items += blocks(item, depth + 1)
                item = item.next
            }
            return items
        }

        /** A bekezdésben lévő képeket külön blokkba emeli, hogy a szöveg folyamatos maradjon. */
        private fun paragraph(node: Paragraph): List<MdBlock> {
            val images = mutableListOf<MdImage>()
            val text = spans(node, images = images)
            return buildList {
                if (text.any { it.text.isNotBlank() }) add(MdBlock.Paragraph(text))
                images.forEach { add(MdBlock.Image(it)) }
            }
        }

        private fun html(literal: String): List<MdBlock> {
            val images = IMG_TAG.findAll(literal).mapNotNull { tag -> imageFromTag(tag.value) }.toList()
            val text = stripHtml(literal)
            return buildList {
                if (text.isNotBlank()) add(MdBlock.Paragraph(listOf(MdSpan(text))))
                images.forEach { add(MdBlock.Image(it)) }
            }
        }

        private fun imageFromTag(tag: String): MdImage? {
            val src = ATTR_SRC.find(tag)?.groupValues?.get(2) ?: return null
            val alt = ATTR_ALT.find(tag)?.groupValues?.get(2).orEmpty()
            return image(decodeEntities(src), decodeEntities(alt))
        }

        private fun table(node: TableBlock): MdBlock.Table {
            var header: List<List<MdSpan>> = emptyList()
            val rows = mutableListOf<List<List<MdSpan>>>()
            for (section in children(node)) {
                for (row in children(section).filterIsInstance<TableRow>()) {
                    val cells = children(row).filterIsInstance<TableCell>().map { spans(it) }.toList()
                    when (section) {
                        is TableHead -> if (header.isEmpty()) header = cells
                        is TableBody -> rows += cells
                    }
                }
            }
            return MdBlock.Table(header, rows)
        }

        private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

        private data class Style(val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

        private fun spans(parent: Node, images: MutableList<MdImage>? = null): List<MdSpan> {
            val out = mutableListOf<MdSpan>()
            collect(parent, Style(), out, images, depth = 0)
            return merge(out)
        }

        private fun collect(parent: Node, style: Style, out: MutableList<MdSpan>, images: MutableList<MdImage>?, depth: Int) {
            var node = parent.firstChild
            while (node != null) {
                when (node) {
                    is Text -> out += MdSpan(node.literal, style.bold, style.italic, link = style.link)
                    is Code -> out += MdSpan(node.literal, style.bold, style.italic, code = true, link = style.link)
                    is SoftLineBreak -> out += MdSpan(" ", style.bold, style.italic, link = style.link)
                    is HardLineBreak -> out += MdSpan("\n", style.bold, style.italic, link = style.link)
                    is Emphasis -> collect(node, style.copy(italic = true), out, images, depth + 1)
                    is StrongEmphasis -> collect(node, style.copy(bold = true), out, images, depth + 1)
                    is Link -> collect(node, style.copy(link = link(node.destination)), out, images, depth + 1)
                    is Image -> {
                        val alt = plainText(node)
                        val img = image(node.destination, alt)
                        if (images != null && img != null) images += img else if (alt.isNotBlank()) out += MdSpan(alt, style.bold, style.italic, link = style.link)
                    }
                    is HtmlInline -> {
                        val literal = node.literal.orEmpty()
                        if (IMG_TAG.containsMatchIn(literal)) {
                            imageFromTag(literal)?.let { img -> if (images != null) images += img }
                        } else if (BR_TAG.matches(literal.trim())) {
                            out += MdSpan("\n", style.bold, style.italic, link = style.link)
                        }
                        // Minden más inline HTML (pl. <b>, <a>) jelölése elhagyva; a köztes szöveg Text csomópontként jön.
                    }
                    else -> if (depth < MAX_DEPTH) collect(node, style, out, images, depth + 1)
                }
                node = node.next
            }
        }

        private fun merge(spans: List<MdSpan>): List<MdSpan> {
            val merged = mutableListOf<MdSpan>()
            for (span in spans) {
                val last = merged.lastOrNull()
                if (last != null && last.bold == span.bold && last.italic == span.italic && last.code == span.code && last.link == span.link) {
                    merged[merged.lastIndex] = last.copy(text = last.text + span.text)
                } else {
                    merged += span
                }
            }
            return merged
        }

        /** A csomópont teljes szövege, iteratív (rekurziómentes) bejárással: tetszőleges mélységnél sem fogy el a verem. */
        private fun plainText(node: Node): String {
            val sb = StringBuilder()
            val stack = ArrayDeque<Node>()
            node.firstChild?.let { stack.addLast(it) }
            while (stack.isNotEmpty() && sb.length < RESTRICTED_CHARS) {
                val current = stack.removeLast()
                current.next?.let { stack.addLast(it) }
                when (current) {
                    is Text -> sb.append(current.literal)
                    is Code -> sb.append(current.literal)
                    is SoftLineBreak, is HardLineBreak -> sb.append(' ')
                    else -> current.firstChild?.let { stack.addLast(it) }
                }
            }
            return sb.toString()
        }

        fun link(destination: String?): String? {
            val raw = destination?.trim().orEmpty()
            if (raw.startsWith("mailto:", ignoreCase = true)) return raw.takeIf { MAILTO.matches(it) }
            return resolve(raw, image = false)?.toString()
        }

        fun image(destination: String?, alt: String): MdImage? {
            val url = resolve(destination, image = true)
            val lower = destination.orEmpty().lowercase()
            val svg = lower.substringBefore('?').endsWith(".svg") || url?.host == "img.shields.io"
            return MdImage(url = url?.toString(), alt = alt.trim().take(200), hostedByGitHub = url?.let { isGitHubHost(it) } ?: false, svg = svg)
        }

        /** Hivatkozás feloldása és szűrése. A képek csak HTTPS-ek lehetnek. */
        private fun resolve(destination: String?, image: Boolean): HttpUrl? {
            val raw = destination?.trim().orEmpty()
            if (raw.isEmpty() || raw.startsWith("#") || raw.length > 2000 || raw.any { it.isISOControl() }) return null
            val colon = raw.indexOf(':')
            val firstSeparator = raw.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (colon > 0 && (firstSeparator < 0 || colon < firstSeparator)) {
                return when (raw.substring(0, colon).lowercase()) {
                    "https" -> raw.toHttpUrlOrNull()
                    "http" -> if (image) null else raw.toHttpUrlOrNull()
                    else -> null // javascript:, data:, file:, intent:, vbscript: és minden más: tiltott
                }
            }
            if (raw.startsWith("//")) return if (image) null else "https:$raw".toHttpUrlOrNull()
            val ctx = context ?: return null
            val path = normalizePath(if (raw.startsWith("/")) raw.drop(1) else ctx.baseDir.trim('/').let { if (it.isEmpty()) raw else "$it/$raw" })
                ?: return null
            val base = if (image) "https://raw.githubusercontent.com/${ctx.owner}/${ctx.repo}/${ctx.ref}/" else "https://github.com/${ctx.owner}/${ctx.repo}/blob/${ctx.ref}/"
            return (base + path).toHttpUrlOrNull()
        }

        private fun normalizePath(path: String): String? {
            val clean = path.substringBefore('#').let { if (it.isEmpty()) return null else it }
            val parts = mutableListOf<String>()
            for (segment in clean.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                    else -> parts += segment
                }
            }
            return parts.joinToString("/").ifEmpty { null }
        }
    }

    internal fun isGitHubHost(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return host == "github.com" || host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }
}
