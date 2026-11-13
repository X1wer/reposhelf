// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.readme

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadmeParserTest {

    private val ctx = LinkContext(owner = "ImranR98", repo = "Obtainium", ref = "main")

    private fun parse(md: String) = ReadmeParser.parse(md, ctx)

    private fun allText(doc: MarkdownDocument): String = buildString {
        fun walk(blocks: List<MdBlock>) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> append(b.text.joinToString("") { it.text }).append('\n')
                    is MdBlock.Paragraph -> append(b.text.joinToString("") { it.text }).append('\n')
                    is MdBlock.Code -> append(b.code).append('\n')
                    is MdBlock.Quote -> walk(b.blocks)
                    is MdBlock.ListBlock -> b.items.forEach(::walk)
                    is MdBlock.Table -> (listOf(b.header) + b.rows).flatten().forEach { cell -> append(cell.joinToString("") { it.text }).append(' ') }
                    else -> Unit
                }
            }
        }
        walk(doc.blocks)
    }

    @Test
    fun supportsCommonElements() {
        val doc = parse(
            """
            # Title
            Some **bold** and *italic* text with `code`.

            - one
            - two

            1. first

            | Name | Value |
            |------|-------|
            | a    | 1     |

            ```kotlin
            fun main() {}
            ```

            > quoted

            ---
            """.trimIndent(),
        )
        assertFalse(doc.restricted)
        val blocks = doc.blocks
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Title"))), blocks[0])
        val para = assertIs<MdBlock.Paragraph>(blocks[1])
        assertTrue(para.text.any { it.bold && it.text == "bold" } && para.text.any { it.italic && it.text == "italic" } && para.text.any { it.code })
        assertEquals(2, assertIs<MdBlock.ListBlock>(blocks[2]).items.size)
        assertTrue(assertIs<MdBlock.ListBlock>(blocks[3]).ordered)
        val table = assertIs<MdBlock.Table>(blocks[4])
        assertEquals(listOf("Name", "Value"), table.header.map { cell -> cell.joinToString("") { it.text } })
        assertEquals(1, table.rows.size)
        assertEquals(MdBlock.Code("fun main() {}", "kotlin"), blocks[5])
        assertIs<MdBlock.Quote>(blocks[6])
        assertEquals(MdBlock.Rule, blocks[7])
    }

    @Test
    fun relativeImagesAndLinks_resolveToTheRepository() {
        val doc = parse("![Logo](./assets/graphics/icon_small.png) [Docs](docs/WIKI.md) [Up](../x.md) [Anchor](#install)")
        val image = doc.blocks.filterIsInstance<MdBlock.Image>().single().image
        assertEquals("https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/icon_small.png", image.url)
        assertTrue(image.hostedByGitHub)
        val links = doc.blocks.filterIsInstance<MdBlock.Paragraph>().flatMap { it.text }.associate { it.text.trim() to it.link }
        assertEquals("https://github.com/ImranR98/Obtainium/blob/main/docs/WIKI.md", links["Docs"])
        assertNull(links["Up"], "a repón kívülre mutató relatív link nem követhető")
        assertNull(links["Anchor"])
    }

    @Test
    fun dangerousLinksAndImages_areDropped() {
        val doc = parse(
            "[x](javascript:alert(1)) [y](data:text/html,<b>) [z](intent://evil) [ok](https://example.org) [mail](mailto:dev@example.org)\n\n" +
                "![insecure](http://example.org/a.png) ![badge](https://img.shields.io/badge/x.svg) ![ext](https://example.org/a.png)",
        )
        val links = doc.blocks.filterIsInstance<MdBlock.Paragraph>().flatMap { it.text }.mapNotNull { it.link }
        assertEquals(listOf("https://example.org/", "mailto:dev@example.org"), links)
        val images = doc.blocks.filterIsInstance<MdBlock.Image>().map { it.image }
        assertNull(images[0].url, "HTTP-kép nem tölthető be")
        assertTrue(images[1].svg, "SVG nem renderelődik")
        assertFalse(images[2].hostedByGitHub, "külső kép csak kérésre töltődik be")
    }

    @Test
    fun html_isNotRendered_butImagesAreKept() {
        val doc = parse(
            """
            <p align="center"><img src="assets/logo.png" alt="Logo" width="100"></p>

            <script>alert('xss')</script>
            <iframe src="https://evil.example"></iframe>
            Text with <b>inline</b> html and <img src="javascript:alert(1)"> done.
            """.trimIndent(),
        )
        val text = allText(doc)
        assertFalse(text.contains("alert"), "a script tartalma nem jelenik meg")
        assertFalse(text.contains("<"), "HTML-tag nem marad a szövegben")
        assertTrue(text.contains("inline"))
        val images = doc.blocks.filterIsInstance<MdBlock.Image>().map { it.image }
        assertEquals("https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/logo.png", images.first().url)
        assertTrue(images.none { it.url?.startsWith("javascript") == true })
    }

    @Test
    fun oversizedInput_fallsBackToRestrictedMode() {
        val doc = parse("# Big\n" + "x".repeat(ReadmeParser.MAX_INPUT_CHARS + 1))
        assertTrue(doc.restricted)
        assertTrue(doc.blocks.all { it is MdBlock.Paragraph })
    }

    @Test
    fun deeplyNestedInput_doesNotCrash() {
        val doc = parse("> ".repeat(2000) + "deep")
        assertTrue(allText(doc).contains("deep") || doc.restricted)
    }

    @Test
    fun withoutContext_relativeReferencesAreDropped() {
        val doc = ReadmeParser.parse("![a](img.png) [b](doc.md)", null)
        assertNull(doc.blocks.filterIsInstance<MdBlock.Image>().singleOrNull()?.image?.url)
        assertTrue(doc.blocks.filterIsInstance<MdBlock.Paragraph>().flatMap { it.text }.all { it.link == null })
    }

    // ---- MD-01: a HTML-eltávolítás lineáris idejű (nincs négyzetes regex-visszalépés) ----

    private fun parseWithin(limitMillis: Long, input: String): MarkdownDocument {
        lateinit var document: MarkdownDocument
        val elapsed = kotlin.system.measureTimeMillis { document = ReadmeParser.parse(input, null) }
        assertTrue(elapsed < limitMillis, "A feldolgozás $elapsed ms-ig tartott (${input.length} karakter, kezdete: ${input.take(24)})")
        return document
    }

    @Test
    fun hostileHtml_isProcessedInLinearTime() {
        // Mindegyik bemenet a régi, regex-alapú eltávolítóval percekig (négyzetes ideig) futott.
        parseWithin(5_000, "<!--" + "<".repeat(500_000))
        parseWithin(5_000, "<!--".repeat(130_000))
        parseWithin(5_000, "<!--" + "<img".repeat(130_000))
        parseWithin(5_000, "<script>".repeat(64_000))
        parseWithin(5_000, "<div>\n" + "<img src=\"".repeat(50_000))
        parseWithin(5_000, "<div " + "src=\"".repeat(100_000) + ">")
        // A kiadási jegyzetek mérete (64 000 karakter): ez korábban a fő szálon futott.
        parseWithin(2_000, "<!--" + "<".repeat(64_000))
    }

    @Test
    fun htmlScanner_keepsTheSameMeaningAsBefore() {
        assertEquals("a b", ReadmeParser.stripHtml("a<!-- hidden --> b"))
        assertEquals("before after", ReadmeParser.stripHtml("before <script>alert(1)</script><STYLE>x{}</STYLE>after"))
        assertEquals("x y", ReadmeParser.stripHtml("<p>x</p> <b>y</b>"))
        assertEquals("1 < 2 & \"q\"", ReadmeParser.stripHtml("1 &lt; 2 &amp; &quot;q&quot;"))
        // Lezáratlan megjegyzés / script: a maradék eldobva (nem jelenik meg szövegként).
        assertEquals("ok", ReadmeParser.stripHtml("ok<!-- never closed <b>secret</b>"))
        assertEquals("ok", ReadmeParser.stripHtml("ok<script>var secret = 1"))
        // Egy magányos záró tag nem nyel el szöveget.
        assertEquals("a b", ReadmeParser.stripHtml("a</script> b"))
        assertEquals("line1\nline2", ReadmeParser.stripHtml("line1<br/>line2"))
        val scan = ReadmeParser.scanHtml("<img src='a.png' alt=\"A\"><IMG SRC=b.png><img src=\"c.png\">", maxImages = 2)
        assertEquals(2, scan.imageTags.size)
        assertEquals(mapOf("src" to "a.png", "alt" to "A"), ReadmeParser.attributes(scan.imageTags[0]))
        assertEquals("b.png", ReadmeParser.attributes(scan.imageTags[1])["src"])
        assertEquals("first", ReadmeParser.attributes("<img src=first src=second>")["src"], "az első előfordulás számít")
    }

    // ---- MD-02: a megjelenített elemek száma korlátos (lista, táblázat, kép) ----

    @Test
    fun hugeListsTablesAndImageFloods_areBounded() {
        // 260 000 üres listaelem: korábban egyetlen blokknak számított.
        val list = parseWithin(10_000, "-\n".repeat(260_000))
        assertTrue(list.restricted, "a túl sok listaelem korlátozott módot ad")
        // Táblázat 130 000 sorral.
        val table = parseWithin(10_000, "|h|\n|-|\n" + "|a|\n".repeat(130_000))
        assertTrue(table.restricted)
        // 5000 kép egyetlen bekezdésben: legfeljebb a keretnyi marad meg.
        val images = parseWithin(10_000, (1..5000).joinToString(" ") { "![i](https://raw.githubusercontent.com/o/r/main/$it.png)" })
        fun count(blocks: List<MdBlock>): Int = blocks.sumOf { block ->
            when (block) {
                is MdBlock.Image -> 1
                is MdBlock.Quote -> count(block.blocks)
                is MdBlock.ListBlock -> block.items.sumOf { count(it) }
                else -> 0
            }
        }
        assertTrue(count(images.blocks) in 1..60, "képek: ${count(images.blocks)}")
        // Egy szokásos méretű dokumentum változatlanul feldolgozódik.
        val normal = ReadmeParser.parse("# T\n\n" + (1..200).joinToString("\n") { "- item $it" } + "\n\n|a|b|\n|-|-|\n" + "|1|2|\n".repeat(100), null)
        assertTrue(!normal.restricted)
    }

    @Test
    fun pathologicalMarkdown_staysFast() {
        parseWithin(10_000, "[".repeat(100_000))
        parseWithin(10_000, "*a ".repeat(60_000))
        parseWithin(10_000, "> ".repeat(100_000) + "x")
        parseWithin(10_000, ("`".repeat(500) + " ").repeat(500))
    }
}
