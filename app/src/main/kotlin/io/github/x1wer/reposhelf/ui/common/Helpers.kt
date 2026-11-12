// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.RepoRef
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Dátum és idő a felhasználó nyelvi beállítása és időzónája szerint. */
fun formatDateTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)

/** A repó webes oldala (csak HTTPS, a szolgáltató saját hosztján). */
fun RepoRef.webUrl(): String = when (provider) {
    ProviderId.GITHUB -> "https://github.com/$owner/$name"
    ProviderId.CODEBERG -> "https://codeberg.org/$owner/$name"
}

fun openUrl(context: Context, url: String) = startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** Rendszerfelület indítása; ha nincs rá alkalmazás (pl. hiányzó böngésző), csendben kimarad. */
fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nincs megfelelő alkalmazás; a felület nem omlik össze.
    } catch (_: SecurityException) {
        // A rendszer megtagadta (pl. hiányzó jogosultság); a felület nem omlik össze.
    }
}
