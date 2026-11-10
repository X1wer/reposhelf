// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.source.readme.MarkdownDocument
import io.github.x1wer.reposhelf.core.source.readme.MdBlock
import io.github.x1wer.reposhelf.core.source.readme.MdImage
import io.github.x1wer.reposhelf.core.source.readme.MdSpan
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Natív, WebView nélküli Markdown-megjelenítés (C. 6.1., D-013). Csak a [MarkdownDocument] korlátozott elemeit ismeri.
 * A linkek már szűrtek (https/http/mailto), a képek csak HTTPS-ek. Külső hosztú kép csak a felhasználó kérésére töltődik.
 */
@Composable
fun MarkdownView(document: MarkdownDocument, showExternalImages: Boolean, onShowExternalImages: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blocks(document.blocks, showExternalImages, onShowExternalImages, depth = 0)
    }
}

@Composable
private fun Blocks(blocks: List<MdBlock>, showExternal: Boolean, onShowExternal: () -> Unit, depth: Int) {
    blocks.forEach { Block(it, showExternal, onShowExternal, depth) }
}

@Composable
private fun Block(block: MdBlock, showExternal: Boolean, onShowExternal: () -> Unit, depth: Int) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is MdBlock.Heading -> Text(
            text = annotated(block.text, linkColor),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.semantics { heading() },
        )
        is MdBlock.Paragraph -> Text(annotated(block.text, linkColor), style = MaterialTheme.typography.bodyMedium)
        is MdBlock.Code -> Text(
            text = block.code,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        )
        is MdBlock.Quote -> Column(
            Modifier.fillMaxWidth().border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(4.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { Blocks(block.blocks, showExternal, onShowExternal, depth + 1) }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(if (block.ordered) "${block.start + index}." else "•", modifier = Modifier.widthIn(min = 20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(item, showExternal, onShowExternal, depth + 1) }
                }
            }
        }
        is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            val rows = listOf(block.header) + block.rows
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        Text(
                            annotated(cell, linkColor),
                            style = if (rowIndex == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                            modifier = Modifier.widthIn(min = 64.dp, max = 240.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(6.dp),
                        )
                    }
                }
            }
        }
        MdBlock.Rule -> HorizontalDivider()
        is MdBlock.Image -> ImageBlock(block.image, showExternal, onShowExternal)
    }
}

@Composable
private fun ImageBlock(image: MdImage, showExternal: Boolean, onShowExternal: () -> Unit) {
    val alt = image.alt.ifBlank { "—" }
    val url = image.url
    when {
        url == null -> Placeholder(stringResource(R.string.image_unavailable, alt))
        image.svg -> Placeholder(stringResource(R.string.image_svg_placeholder, alt))
        !image.hostedByGitHub && !showExternal -> Column {
            Placeholder(stringResource(R.string.image_external_placeholder, url.toHttpUrlOrNull()?.host ?: alt))
            OutlinedButton(onClick = onShowExternal) { Text(stringResource(R.string.action_load_external_images)) }
        }
        else -> AsyncImage(
            model = url,
            contentDescription = image.alt.ifBlank { null },
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        )
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(8.dp),
    )
}

private fun annotated(spans: List<MdSpan>, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
        )
        val link = span.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
