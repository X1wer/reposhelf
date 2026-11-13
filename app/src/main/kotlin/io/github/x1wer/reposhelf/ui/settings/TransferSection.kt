// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import io.github.x1wer.reposhelf.core.data.tracked.ImportPreview
import io.github.x1wer.reposhelf.core.data.transfer.AppListEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.transfer.AppListDecodeResult
import io.github.x1wer.reposhelf.core.data.transfer.AppListFormat
import io.github.x1wer.reposhelf.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchGuard

/**
 * Exportálás és importálás (C. 25.): a rendszer fájlválasztójával (Storage Access Framework, tárhely-engedély nélkül).
 * Az import csak listát és beállítást ír; semmit nem telepít, nem tölt le, és nem kér le a hálózatról.
 */
@Composable
fun TransferSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { message = export(context, container, uri) }
    }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val read = readImport(context, container, uri)) {
                    is ImportRead.Failed -> message = read.message
                    is ImportRead.Ready -> pending = read.pending
                }
            }
        }
    }
    // Az importfájl megbízhatatlan: a felhasználó az írás ELŐTT látja, mit tartalmaz (különösen az új saját forrásokat).
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.transfer_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.transfer_confirm_summary, p.preview.newFromCatalog, p.preview.newOwnSources.size, p.preview.existing, p.skipped))
                    if (p.preview.newOwnSources.isNotEmpty()) {
                        Text(stringResource(R.string.transfer_confirm_own_sources), style = MaterialTheme.typography.bodySmall)
                        p.preview.newOwnSources.take(MAX_SHOWN_SOURCES).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        val more = p.preview.newOwnSources.size - MAX_SHOWN_SOURCES
                        if (more > 0) Text(pluralStringResource(R.plurals.transfer_confirm_more, more, more), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.transfer_confirm_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                ObscuredTouchGuard()
                TextButton(onClick = {
                    pending = null
                    scope.launch { message = applyImport(context, container, p) }
                }) { Text(stringResource(R.string.transfer_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Text(stringResource(R.string.transfer_note), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { exportLauncher.launch("reposhelf-apps.json") }) { Text(stringResource(R.string.transfer_export)) }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
            Text(stringResource(R.string.transfer_import))
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

private suspend fun export(context: Context, container: AppContainer, uri: Uri): String {
    val entries = container.trackedApps.exportList()
    val bytes = AppListFormat.encode(entries, Instant.now())
    return try {
        withContext(Dispatchers.IO) {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no stream" }.use { it.write(bytes) }
        }
        context.getString(R.string.transfer_exported, entries.size)
    } catch (_: IOException) {
        context.getString(R.string.transfer_write_failed)
    } catch (_: IllegalStateException) {
        context.getString(R.string.transfer_write_failed)
    } catch (_: SecurityException) {
        context.getString(R.string.transfer_write_failed)
    }
}

/** Egy beolvasott, de még NEM alkalmazott import: a bejegyzések, az előnézet és a kihagyott hibás bejegyzések száma. */
private class PendingImport(val entries: List<AppListEntry>, val preview: ImportPreview, val skipped: Int)

private sealed interface ImportRead {
    class Failed(val message: String) : ImportRead
    class Ready(val pending: PendingImport) : ImportRead
}

private const val MAX_SHOWN_SOURCES = 8

/** Beolvasás és előnézet: semmit nem ír. A feldolgozás nem a fő szálon fut. */
private suspend fun readImport(context: Context, container: AppContainer, uri: Uri): ImportRead {
    val bytes = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(AppListFormat.MAX_BYTES) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } ?: return ImportRead.Failed(context.getString(R.string.transfer_read_failed))
    return when (val decoded = withContext(Dispatchers.Default) { AppListFormat.decode(bytes) }) {
        is AppListDecodeResult.Invalid -> ImportRead.Failed(
            context.getString(
                when (decoded.reason) {
                    AppListDecodeResult.Invalid.Reason.TOO_LARGE, AppListDecodeResult.Invalid.Reason.TOO_MANY_ENTRIES -> R.string.transfer_too_large
                    AppListDecodeResult.Invalid.Reason.UNSUPPORTED_VERSION -> R.string.transfer_unsupported
                    AppListDecodeResult.Invalid.Reason.MALFORMED, AppListDecodeResult.Invalid.Reason.NOT_AN_APP_LIST -> R.string.transfer_not_a_list
                },
            ),
        )
        is AppListDecodeResult.Decoded ->
            if (decoded.entries.isEmpty()) {
                ImportRead.Failed(context.getString(R.string.transfer_nothing_to_import, decoded.skipped.size))
            } else {
                ImportRead.Ready(PendingImport(decoded.entries, container.trackedApps.previewImport(decoded.entries), decoded.skipped.size))
            }
    }
}

/** Az import alkalmazása a felhasználó megerősítése UTÁN. */
private suspend fun applyImport(context: Context, container: AppContainer, pending: PendingImport): String {
    val summary = container.trackedApps.importList(pending.entries)
    return buildString {
        append(context.getString(R.string.transfer_imported, summary.added, summary.updated, pending.skipped))
        if (summary.automationNotImported > 0) append(' ').append(context.getString(R.string.transfer_auto_install_not_imported, summary.automationNotImported))
        if (summary.existingSettingsKept > 0) append(' ').append(context.getString(R.string.transfer_existing_settings_kept, summary.existingSettingsKept))
    }
}
