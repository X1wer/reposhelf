// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Kezdőképernyős parancsikon (C. 8.). Elsődlegesen a launcher maga teszi ki az ikont (`INSTALL_REASON_USER`);
 * ez a kiegészítő, felhasználó által kért lehetőség. A parancsikon a mi alkalmazásunkhoz tartozik, és a rendszer
 * jóváhagyást kér hozzá. Garancia nincs: a launchertől függ, hogy támogatja-e.
 */
object Shortcuts {
    fun isSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Csak előtérben hívható. `false`, ha a launcher nem támogatja, vagy az app nem indítható. */
    fun requestPin(context: Context, packageName: String): Boolean {
        if (!isSupported(context)) return false
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return false
        val component = launch.component ?: return false
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrNull() ?: return false
        val icon = runCatching { pm.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shortcut = ShortcutInfoCompat.Builder(context, "pkg:$packageName")
            .setShortLabel(label.take(MAX_LABEL))
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    }

    private const val ICON_PX = 192
    private const val MAX_LABEL = 25
}
