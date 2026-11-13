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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.network.ImageHttpClient
import io.github.x1wer.reposhelf.ui.common.openUrl
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
    val context = LocalContext.current
    // A README megbízhatatlan szöveg: a link felirata mást mutathat, mint a tényleges cél. A GitHubon kívülre mutató
    // hivatkozás ezért csak megerősítés után nyílik meg, a TELJES célcím megmutatásával.
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val onLink: (String) -> Unit = { url -> if (isGitHubLink(url)) openUrl(context, url) else pendingLink = url }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blocks(document.blocks, showExternalImages, onShowExternalImages, onLink, depth = 0)
    }
    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.link_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.link_confirm_note), style = MaterialTheme.typography.bodySmall)
                    Text(url.take(500), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    openUrl(context, url)
                }) { Text(stringResource(R.string.link_confirm_open)) }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** HTTPS-cím a GitHub saját hosztján: megerősítés nélkül megnyitható. */
internal fun isGitHubLink(url: String): Boolean = url.toHttpUrlOrNull()?.let { it.isHttps && ImageHttpClient.isGitHubHost(it.host) } == true

@Composable
private fun Blocks(blocks: List<MdBlock>, showExternal: Boolean, onShowExternal: () -> Unit, onLink: (String) -> Unit, depth: Int) {
    blocks.forEach { Block(it, showExternal, onShowExternal, onLink, depth) }
}

@Composable
private fun Block(block: MdBlock, showExternal: Boolean, onShowExternal: () -> Unit, onLink: (String) -> Unit, depth: Int) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is MdBlock.Heading -> Text(
            text = annotated(block.text, linkColor, onLink),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.semantics { heading() },
        )
        is MdBlock.Paragraph -> Text(annotated(block.text, linkColor, onLink), style = MaterialTheme.typography.bodyMedium)
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
        ) { Blocks(block.blocks, showExternal, onShowExternal, onLink, depth + 1) }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(if (block.ordered) "${block.start + index}." else "•", modifier = Modifier.widthIn(min = 20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(item, showExternal, onShowExternal, onLink, depth + 1) }
                }
            }
        }
        is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            val rows = listOf(block.header) + block.rows
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        Text(
                            annotated(cell, linkColor, onLink),
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
        else -> {
            val platformContext = LocalPlatformContext.current
            // GitHub-tárhelyű képnél a betöltő minden átirányítási lépést GitHub-hosztra korlátoz. Külső képnél (a
            // felhasználó kérésére) a kérés belső jelölést kap, amely a hálózatra nem megy ki.
            val model = remember(url, image.hostedByGitHub) {
                val builder = ImageRequest.Builder(platformContext).data(url)
                if (!image.hostedByGitHub) builder.httpHeaders(NetworkHeaders.Builder().set(ImageHttpClient.EXTERNAL_CONSENT_HEADER, "1").build())
                builder.build()
            }
            AsyncImage(
                model = model,
                contentDescription = image.alt.ifBlank { null },
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            )
        }
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

private fun annotated(spans: List<MdSpan>, linkColor: androidx.compose.ui.graphics.Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
        )
        val link = span.link
        if (link != null) {
            // Saját kattintáskezelő: a cél ellenőrzése (és külső címnél megerősítés) után nyílik meg, nem közvetlenül.
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { onLink(link) }) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
