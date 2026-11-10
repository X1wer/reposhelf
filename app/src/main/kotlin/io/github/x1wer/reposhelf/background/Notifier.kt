// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.x1wer.reposhelf.MainActivity
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.notifications.NotificationLog

/**
 * Értesítések (C. 13., docs/ARCHITECTURE.md 12. pont, D-063).
 * - 7 csatorna egy csoportban: a C. 13. hat kategóriája + a technikai `progress`; a frissítési csatorna így külön állítható.
 * - Deduplikáció: ugyanarról a változatlan állapotról nincs újabb értesítés ([NotificationLog]); az állandó (tag, id) pár
 *   miatt egy újabb értesítés lecseréli az előzőt.
 * - Megnyitás: explicit, immutábilis PendingIntent a saját `MainActivity`-re, belső azonosítóval (nincs trampoline, nincs
 *   exportált deep link).
 * - Ha az értesítés nem küldhető (nincs engedély, a csatorna tiltva), nem rögzítjük elküldöttként: az alkalmazáson belüli
 *   jelzés (Frissítések képernyő) ettől függetlenül látszik (C. 13.).
 */
class Notifier(private val context: Context, private val log: NotificationLog) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannelGroup(NotificationChannelGroup(GROUP, context.getString(R.string.notif_group)))
        listOf(
            Triple(CH_UPDATES, R.string.notif_channel_updates, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_INSTALLED, R.string.notif_channel_installed, NotificationManager.IMPORTANCE_LOW),
            Triple(CH_FAILURES, R.string.notif_channel_failures, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_SECURITY, R.string.notif_channel_security, NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_CATALOG, R.string.notif_channel_catalog, NotificationManager.IMPORTANCE_LOW),
            Triple(CH_SELF_UPDATE, R.string.notif_channel_self_update, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_PROGRESS, R.string.notif_channel_progress, NotificationManager.IMPORTANCE_MIN),
        ).forEach { (id, name, importance) ->
            system.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply { group = GROUP })
        }
    }

    /** Küldhető-e értesítés ezen a csatornán (engedély, alkalmazásszintű tiltás, csatornaszintű tiltás). */
    fun canPost(channel: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        val importance = manager.getNotificationChannel(channel)?.importance ?: return true
        return importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun updateAvailable(trackedId: Long, appName: String, version: String, releaseId: Long) =
        post(CH_UPDATES, "update:$trackedId", "release:$releaseId", trackedId, ID_UPDATE) {
            setContentTitle(context.getString(R.string.notif_update_title, appName)).setContentText(context.getString(R.string.notif_update_text, version))
        }

    suspend fun readyToInstall(trackedId: Long, appName: String, version: String, releaseId: Long) =
        post(CH_UPDATES, "ready:$trackedId", "release:$releaseId", trackedId, ID_UPDATE) {
            setContentTitle(context.getString(R.string.notif_ready_title, appName)).setContentText(context.getString(R.string.notif_ready_text, version))
        }

    /** A rendszer jóváhagyást kér a háttérben indított telepítéshez (normál működés, nem hiba; C. 12.4.). */
    suspend fun confirmInstall(trackedId: Long, appName: String, sessionId: Int) =
        post(CH_UPDATES, "confirm:$trackedId", "session:$sessionId", trackedId, ID_UPDATE) {
            setContentTitle(context.getString(R.string.notif_confirm_title, appName)).setContentText(context.getString(R.string.notif_confirm_text))
        }

    suspend fun installed(trackedId: Long, appName: String, version: String) {
        cancel(trackedId, ID_UPDATE)
        log.clear("update:$trackedId")
        log.clear("ready:$trackedId")
        post(CH_INSTALLED, "installed:$trackedId", "version:$version", trackedId, ID_INSTALLED) {
            setContentTitle(context.getString(R.string.notif_installed_title, appName)).setContentText(context.getString(R.string.notif_installed_text, version))
        }
    }

    suspend fun failed(trackedId: Long, appName: String, reason: String, stateKey: String) =
        post(CH_FAILURES, "failed:$trackedId", stateKey, trackedId, ID_FAILED) {
            setContentTitle(context.getString(R.string.notif_failed_title, appName)).setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
        }

    suspend fun security(trackedId: Long, appName: String, text: String, stateKey: String) =
        post(CH_SECURITY, "security:$trackedId", stateKey, trackedId, ID_SECURITY) {
            setContentTitle(context.getString(R.string.notif_security_title, appName)).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

    suspend fun catalogUpdated(appCount: Int, stateKey: String) =
        post(CH_CATALOG, "catalog", stateKey, null, ID_CATALOG) {
            setContentTitle(context.getString(R.string.notif_catalog_title)).setContentText(context.resources.getQuantityString(R.plurals.notif_catalog_text, appCount, appCount))
        }

    private fun cancel(trackedId: Long, id: Int) = manager.cancel(trackedId.toString(), id)

    @SuppressLint("MissingPermission") // a canPost ellenőrzi a POST_NOTIFICATIONS engedélyt
    private suspend fun post(channel: String, key: String, state: String, trackedId: Long?, id: Int, content: NotificationCompat.Builder.() -> NotificationCompat.Builder) {
        if (!canPost(channel)) return
        if (!log.claim(key, state)) return
        val notification = NotificationCompat.Builder(context, channel)
            .content()
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(trackedId))
            .build()
        manager.notify(trackedId?.toString() ?: key, id, notification)
    }

    /** Az értesítés a saját activitynket nyitja meg, a követett alkalmazás belső azonosítójával (validálva fogadjuk). */
    private fun openIntent(trackedId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        trackedId?.let { intent.putExtra(EXTRA_OPEN_TRACKED, it) }
        return PendingIntent.getActivity(context, (trackedId ?: 0L).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A rendszer értesítési beállításai ehhez az apphoz (a felhasználó itt kapcsolhatja ki/be a csatornákat). */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    companion object {
        const val GROUP = "reposhelf"
        const val CH_UPDATES = "updates"
        const val CH_INSTALLED = "installed"
        const val CH_FAILURES = "failures"
        const val CH_SECURITY = "security"
        const val CH_CATALOG = "catalog"
        const val CH_SELF_UPDATE = "self_update"
        const val CH_PROGRESS = "progress"

        const val EXTRA_OPEN_TRACKED = "io.github.x1wer.reposhelf.extra.OPEN_TRACKED"

        private const val ID_UPDATE = 1
        private const val ID_INSTALLED = 2
        private const val ID_FAILED = 3
        private const val ID_SECURITY = 4
        private const val ID_CATALOG = 5
    }
}
