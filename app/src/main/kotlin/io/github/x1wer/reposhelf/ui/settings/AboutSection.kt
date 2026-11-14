// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.BuildConfig
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A beépített licencszövegek (a build másolja az APK-ba, D-138). */
private enum class LicenseText(val asset: String, val title: Int, val reflow: Boolean) {
    /** Kemény sortörésű egyszerű szöveg: keskeny képernyőn bekezdésenként újratördelve olvasható. */
    APP("licenses/LICENSE", R.string.about_license_title, reflow = true),
    THIRD_PARTY("licenses/THIRD_PARTY_NOTICES.md", R.string.about_notices_title, reflow = false),
}

/** A bekezdéseken belüli sortörések szóközzé alakítása (bekezdéshatár: üres sor). */
private fun reflow(text: String): String =
    text.split(Regex("\n\\s*\n")).joinToString("\n\n") { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }

/** Névjegy: verzió, licenc, a harmadik féltől származó komponensek közleményei (Q-001 kiegészítő javaslata, D-136). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSection() {
    var shown by rememberSaveable { mutableStateOf<LicenseText?>(null) }
    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.about_license_note), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { shown = LicenseText.APP }) { Text(stringResource(R.string.about_show_license)) }
        OutlinedButton(onClick = { shown = LicenseText.THIRD_PARTY }) { Text(stringResource(R.string.about_show_notices)) }
    }
    shown?.let { text -> LicenseDialog(text) { shown = null } }
}

@Composable
private fun LicenseDialog(text: LicenseText, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // "" = betöltés alatt, null = nem olvasható.
    val content by produceState<String?>("", text) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(text.asset).bufferedReader().use { it.readText() } }.getOrNull()
                // A fájl eleji SPDX-megjegyzések (`<!-- … -->`) a felületen fölöslegesek.
                ?.lines()?.dropWhile { it.startsWith("<!--") || it.isBlank() }?.joinToString("\n")
                ?.let { if (text.reflow) reflow(it) else it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(text.title)) },
        text = {
            Text(
                content ?: stringResource(R.string.about_text_unavailable),
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            ObscuredTouchGuard()
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
