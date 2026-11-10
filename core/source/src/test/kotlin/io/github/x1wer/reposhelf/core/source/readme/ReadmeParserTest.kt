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
}
