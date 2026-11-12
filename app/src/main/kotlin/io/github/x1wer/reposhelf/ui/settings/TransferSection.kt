// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = import(context, container, uri) }
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

private suspend fun import(context: Context, container: AppContainer, uri: Uri): String {
    val bytes = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(AppListFormat.MAX_BYTES) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } ?: return context.getString(R.string.transfer_read_failed)
    return when (val decoded = AppListFormat.decode(bytes)) {
        is AppListDecodeResult.Invalid -> context.getString(
            when (decoded.reason) {
                AppListDecodeResult.Invalid.Reason.TOO_LARGE, AppListDecodeResult.Invalid.Reason.TOO_MANY_ENTRIES -> R.string.transfer_too_large
                AppListDecodeResult.Invalid.Reason.UNSUPPORTED_VERSION -> R.string.transfer_unsupported
                AppListDecodeResult.Invalid.Reason.MALFORMED, AppListDecodeResult.Invalid.Reason.NOT_AN_APP_LIST -> R.string.transfer_not_a_list
            },
        )
        is AppListDecodeResult.Decoded -> {
            val summary = container.trackedApps.importList(decoded.entries)
            buildString {
                append(context.getString(R.string.transfer_imported, summary.added, summary.updated, decoded.skipped.size))
                if (summary.autoInstallNotImported > 0) append(' ').append(context.getString(R.string.transfer_auto_install_not_imported, summary.autoInstallNotImported))
            }
        }
    }
}
