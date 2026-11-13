// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.readme

import io.github.x1wer.reposhelf.core.network.ImageHttpClient
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

    /**
     * A megjelenítendő elemek keretei: a felület a teljes dokumentumot egyszerre komponálja, ezért a listaelemek, a
     * táblázatcellák, a képek és a szöveg mennyisége is korlátos (a blokkszám önmagában nem elég: egy lista vagy egy
     * táblázat egyetlen blokk). Túllépésnél korlátozott (csak szöveg) mód; a képekből a kereten felüliek kimaradnak.
     */
    private const val MAX_ITEMS = 2000
    private const val MAX_COLUMNS = 20
    private const val MAX_IMAGES = 60
    private const val MAX_TEXT_CHARS = 300_000
    private const val MAX_TAG_CHARS = 4096

    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")
    private val MAILTO = Regex("^mailto:[^\\s<>\"']{3,254}$", RegexOption.IGNORE_CASE)

    fun parse(markdown: String, context: LinkContext?): MarkdownDocument {
        if (markdown.length > MAX_INPUT_CHARS) return restricted(markdown)
        return try {
            val builder = Builder(context)
            val blocks = builder.blocks(parser.parse(markdown), depth = 0)
            if (builder.blockCount > MAX_BLOCKS || builder.overBudget) restricted(markdown) else MarkdownDocument(blocks)
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

    internal fun stripHtml(html: String): String = cleanText(scanHtml(html, maxImages = 0).text)

    private fun cleanText(text: CharSequence): String = decodeEntities(text.toString()).replace(WHITESPACE, " ").trim()

    /** A HTML-eltávolítás eredménye: a megmaradó szöveg és (legfeljebb a kért számú) `<img …>` tag. */
    internal class HtmlScan(val text: CharSequence, val imageTags: List<String>)

    /**
     * HTML-eltávolítás EGYETLEN, lineáris idejű bejárással (reguláris kifejezés nélkül): a megjegyzések és a
     * `<script>` / `<style>` elemek tartalmukkal együtt eldobva, a `<br>` sortörés, minden más tag jelölése törölve.
     * A lezáratlan megjegyzés, script vagy tag után a bejárás leáll (a lezáratlan megjegyzés és script maradéka
     * eldobva), így a rosszindulatúan kialakított bemenet sem okoz négyzetes futási időt.
     */
    internal fun scanHtml(html: String, maxImages: Int): HtmlScan {
        val out = StringBuilder(minOf(html.length, 64 * 1024))
        val images = ArrayList<String>()
        val n = html.length
        var i = 0
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(html, i, n)
                break
            }
            out.append(html, i, lt)
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                if (end < 0) break
                i = end + 3
                continue
            }
            val gt = html.indexOf('>', lt + 1)
            if (gt < 0) {
                // Nincs több lezárt tag: a maradék egyszerű szöveg.
                out.append(html, lt, n)
                break
            }
            when (val name = tagName(html, lt + 1, gt)) {
                "script", "style" -> {
                    val close = html.indexOf("</$name", gt + 1, ignoreCase = true)
                    val closeEnd = if (close < 0) -1 else html.indexOf('>', close)
                    if (closeEnd < 0) break
                    i = closeEnd + 1
                    continue
                }
                "br" -> out.append('\n')
                "img" -> if (images.size < maxImages && gt - lt <= MAX_TAG_CHARS) images += html.substring(lt, gt + 1)
            }
            i = gt + 1
        }
        return HtmlScan(out, images)
    }

    /** A tag kisbetűs neve; záró tagnál `/` előtaggal (így egy magányos `</script>` nem számít nyitónak). */
    internal fun tagName(html: String, start: Int, end: Int): String {
        var i = start
        val closing = i < end && html[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < end && i - nameStart < 12 && html[i].isLetterOrDigit()) i++
        val name = html.substring(nameStart, i).lowercase()
        return if (closing) "/$name" else name
    }

    /** Egy HTML-tag attribútumai, lineáris bejárással (idézőjeles és idézőjel nélküli értékek; az első előfordulás számít). */
    internal fun attributes(tag: String): Map<String, String> {
        val result = HashMap<String, String>()
        val n = tag.length
        var i = tag.indexOfFirst { it.isWhitespace() }
        if (i < 0) return result
        while (i < n && result.size < 16) {
            while (i < n && (tag[i].isWhitespace() || tag[i] == '/')) i++
            val nameStart = i
            while (i < n && !tag[i].isWhitespace() && tag[i] != '=' && tag[i] != '>' && tag[i] != '/') i++
            if (i == nameStart) {
                i++
                continue
            }
            val name = tag.substring(nameStart, i).lowercase()
            while (i < n && tag[i].isWhitespace()) i++
            if (i >= n || tag[i] != '=') continue
            i++
            while (i < n && tag[i].isWhitespace()) i++
            val value: String
            if (i < n && (tag[i] == '"' || tag[i] == '\'')) {
                val end = tag.indexOf(tag[i], i + 1)
                if (end < 0) break
                value = tag.substring(i + 1, end)
                i = end + 1
            } else {
                val valueStart = i
                while (i < n && !tag[i].isWhitespace() && tag[i] != '>') i++
                value = tag.substring(valueStart, i)
            }
            if (name !in result) result[name] = value
        }
        return result
    }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private class Builder(private val context: LinkContext?) {
        var blockCount = 0

        /** A listaelemek és táblázatcellák száma, a képek száma és a szöveg hossza (a megjelenítés keretei). */
        private var itemCount = 0
        private var imageCount = 0
        private var textChars = 0
        var overBudget = false
            private set

        private fun admitImage(): Boolean = ++imageCount <= MAX_IMAGES

        private fun countText(chars: Int) {
            textChars += chars
            if (textChars > MAX_TEXT_CHARS) overBudget = true
        }

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
                is FencedCodeBlock -> listOf(code(node.literal, node.info?.takeIf { it.isNotBlank() }?.take(20)))
                is IndentedCodeBlock -> listOf(code(node.literal, null))
                is BlockQuote -> listOf(MdBlock.Quote(blocks(node, depth + 1)))
                is BulletList -> listOf(MdBlock.ListBlock(false, 1, items(node, depth)))
                is OrderedList -> listOf(MdBlock.ListBlock(true, node.markerStartNumber ?: 1, items(node, depth)))
                is ThematicBreak -> listOf(MdBlock.Rule)
                is HtmlBlock -> html(node.literal.orEmpty())
                is TableBlock -> listOf(table(node))
                else -> plainText(node).takeIf { it.isNotBlank() }?.let { listOf(MdBlock.Paragraph(listOf(MdSpan(it)))) }
            }?.takeIf { it.isNotEmpty() }
        }

        private fun code(literal: String?, language: String?): MdBlock.Code {
            val text = literal.orEmpty().take(MAX_CODE_CHARS).trimEnd()
            countText(text.length)
            return MdBlock.Code(text, language)
        }

        private fun items(list: Node, depth: Int): List<List<MdBlock>> {
            val items = mutableListOf<List<MdBlock>>()
            var item = list.firstChild
            while (item != null) {
                if (item is ListItem) {
                    // Az üres listaelem is megjelenített elem: beleszámít a keretbe (különben egy lista korlátlan lehetne).
                    if (++itemCount > MAX_ITEMS) {
                        overBudget = true
                        break
                    }
                    items += blocks(item, depth + 1)
                }
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
                images.forEach { if (admitImage()) add(MdBlock.Image(it)) }
            }
        }

        private fun html(literal: String): List<MdBlock> {
            val scan = scanHtml(literal, MAX_IMAGES)
            val images = scan.imageTags.mapNotNull { tag -> imageFromTag(tag) }
            val text = cleanText(scan.text)
            countText(text.length)
            return buildList {
                if (text.isNotBlank()) add(MdBlock.Paragraph(listOf(MdSpan(text))))
                images.forEach { if (admitImage()) add(MdBlock.Image(it)) }
            }
        }

        private fun imageFromTag(tag: String): MdImage? {
            if (tag.length > MAX_TAG_CHARS) return null
            val attributes = attributes(tag)
            val src = attributes["src"] ?: return null
            return image(decodeEntities(src), decodeEntities(attributes["alt"].orEmpty()))
        }

        private fun table(node: TableBlock): MdBlock.Table {
            var header: List<List<MdSpan>> = emptyList()
            val rows = mutableListOf<List<List<MdSpan>>>()
            for (section in children(node)) {
                for (row in children(section).filterIsInstance<TableRow>()) {
                    val cellNodes = children(row).filterIsInstance<TableCell>().take(MAX_COLUMNS).toList()
                    // A sorok és cellák megjelenített elemek: beleszámítanak a keretbe.
                    itemCount += cellNodes.size.coerceAtLeast(1)
                    if (itemCount > MAX_ITEMS) {
                        overBudget = true
                        return MdBlock.Table(header, rows)
                    }
                    val cells = cellNodes.map { spans(it) }
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
            return merge(out).also { merged -> countText(merged.sumOf { it.text.length }) }
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
                        val literal = node.literal.orEmpty().trim()
                        when (if (literal.startsWith("<")) tagName(literal, 1, literal.length) else "") {
                            "img" -> imageFromTag(literal)?.let { img -> if (images != null) images += img }
                            "br" -> out += MdSpan("\n", style.bold, style.italic, link = style.link)
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
        // Ugyanaz a szabály dönt a besorolásról és a képbetöltő átirányítás-ellenőrzéséről (MD-03).
        return ImageHttpClient.isGitHubHost(url.host)
    }
}
