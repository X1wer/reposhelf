// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import kotlinx.coroutines.launch

/**
 * Az értesítési engedély kérése a megfelelő pillanatban, magyarázattal (Android 13+; docs/ARCHITECTURE.md 12.):
 * nem első indításkor, hanem amikor a felhasználó először követ egy alkalmazást, vagy bekapcsolja az értesítéseket.
 *
 * [onlyOnce]: `true` esetén (pl. követéskor) csak egyszer kérdezünk; a Beállításokban kifejezett bekapcsoláskor mindig.
 */
@Composable
fun NotificationPermissionGate(onlyOnce: Boolean = false, content: @Composable (request: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as RepoShelfApplication).container.settings
    val scope = rememberCoroutineScope()
    var showRationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val request: () -> Unit = request@{
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@request
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@request
        scope.launch { if (!onlyOnce || !settings.notificationPermissionAsked()) showRationale = true }
    }
    content(request)

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                scope.launch { settings.markNotificationPermissionAsked() }
            },
            title = { Text(stringResource(R.string.notif_permission_title)) },
            text = { Text(stringResource(R.string.notif_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    scope.launch { settings.markNotificationPermissionAsked() }
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notif_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    scope.launch { settings.markNotificationPermissionAsked() }
                }) { Text(stringResource(R.string.notif_permission_later)) }
            },
        )
    }
}
