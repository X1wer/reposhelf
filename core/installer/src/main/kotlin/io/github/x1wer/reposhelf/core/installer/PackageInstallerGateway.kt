// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import io.github.x1wer.reposhelf.core.update.InstallFailureKind
import io.github.x1wer.reposhelf.core.update.InstallRequest
import io.github.x1wer.reposhelf.core.update.InstallSignal
import io.github.x1wer.reposhelf.core.update.InstallStart
import io.github.x1wer.reposhelf.core.update.PackageInstallPort
import java.io.IOException
import java.security.MessageDigest

/** A PackageInstaller által küldött eredmény (docs/ARCHITECTURE.md 11.1.). */
sealed interface InstallEvent {
    val sessionId: Int

    data class Success(override val sessionId: Int, val packageName: String?) : InstallEvent

    /** A rendszer felhasználói jóváhagyást kér: az [intent]-et az előtérben lévő activity indítja el. */
    data class PendingUserAction(override val sessionId: Int, val intent: Intent) : InstallEvent

    data class Failure(
        override val sessionId: Int,
        val status: Int,
        val message: String?,
        /** Android 16 QPR2+: a developer verification hibakódja (0 ismeretlen, 1 nincs hálózat, 2 fejlesztő letiltva). */
        val developerVerificationReason: Int?,
        /** Ha van: további magyarázó felület a rendszertől (pl. developer verification). */
        val explanationIntent: Intent?,
    ) : InstallEvent
}

/** A rendszer eredményének platformfüggetlen alakja a 8. lépéshez (docs/ARCHITECTURE.md 11.1., hibakód-leképezés). */
fun InstallEvent.toSignal(): InstallSignal? = when (this) {
    is InstallEvent.Success -> InstallSignal.Success(packageName)
    is InstallEvent.PendingUserAction -> null
    is InstallEvent.Failure -> InstallSignal.Failure(
        when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailureKind.CONFLICT
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailureKind.INVALID
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailureKind.INCOMPATIBLE
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailureKind.BLOCKED
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailureKind.STORAGE
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                if (developerVerificationReason != null) InstallFailureKind.DEVELOPER_VERIFICATION else InstallFailureKind.ABORTED
            else -> InstallFailureKind.OTHER
        },
        message,
    )
}

/** A telepítési események folyamaton belüli csatornája (a receiver ide küld, a felület innen olvas). */
object InstallEvents {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()

    /**
     * A még meg nem jelenített jóváhagyási kérés. Állapotként tároljuk, hogy akkor se vesszen el, ha az esemény
     * pillanatában nincs előtérben lévő activity; ilyenkor visszatéréskor jelenik meg. Az értesítés a 5. munkamenetben jön.
     */
    private val _pendingConfirmation = MutableStateFlow<InstallEvent.PendingUserAction?>(null)
    val pendingConfirmation: StateFlow<InstallEvent.PendingUserAction?> = _pendingConfirmation.asStateFlow()

    fun consumePendingConfirmation(event: InstallEvent.PendingUserAction) {
        _pendingConfirmation.compareAndSet(event, null)
    }

    internal fun emit(event: InstallEvent) {
        if (event is InstallEvent.PendingUserAction) _pendingConfirmation.value = event
        _events.tryEmit(event)
    }
}

/**
 * Telepítés kizárólag a hivatalos PackageInstaller-sessionnel (C. 7.3.):
 * - a csomagnév a sessionben is rögzített (`setAppPackageName`), így a rendszer elutasítja az eltérő csomagot;
 * - `INSTALL_REASON_USER`: az AOSP/Pixel launcher ettől teszi ki az ikont (C. 8.);
 * - csomagforrás: katalógusbeli app → STORE, URL-lel hozzáadott → DOWNLOADED_FILE (D-044);
 * - első telepítéskor update ownership kérése (Android 14+, D-043);
 * - a sessionbe írt bájtok SHA-256-ja commit előtt újra ellenőrzött (nincs rés az ellenőrzés és a telepítés között);
 * - a státusz egy nem exportált receiverre érkezik, explicit és mutábilis PendingIntent-tel (target 35+ követelmény).
 */
class PackageInstallerGateway(
    private val context: Context,
    /** Update ownership kérése első telepítéskor (D-043; alapból be, a beállításokban kikapcsolható lesz). */
    private val requestUpdateOwnership: () -> Boolean = { true },
) : PackageInstallPort {

    override suspend fun install(request: InstallRequest): InstallStart = withContext(Dispatchers.IO) {
        val files = request.files
        val packageName = request.packageName
        val fromCatalog = request.fromCatalog
        val isUpdate = request.isUpdate
        require(files.isNotEmpty())
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(files.sumOf { it.file.length() })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Alapból mindig felhasználói jóváhagyással (C. 12.5.). Beavatkozás nélkül csak akkor, ha az
                // UnattendedInstallPolicy minden feltételt igazolt (ARCH 11.2.); a rendszer ekkor is kérhet jóváhagyást.
                setRequireUserAction(
                    if (request.unattended && isUpdate) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(if (fromCatalog) PackageInstaller.PACKAGE_SOURCE_STORE else PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && requestUpdateOwnership() && !isUpdate) {
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            return@withContext InstallStart.Failed(e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return@withContext InstallStart.Failed(e.javaClass.simpleName)
        }
        try {
            installer.openSession(sessionId).use { session ->
                files.forEachIndexed { index, apk ->
                    val name = if (!apk.isSplit) "base.apk" else "split_$index.apk"
                    session.openWrite(name, 0, apk.file.length()).use { out ->
                        apk.file.inputStream().use { it.copyTo(out, BUFFER) }
                        session.fsync(out)
                    }
                    // A ténylegesen a sessionbe került bájtok újraellenőrzése (TOCTOU ellen).
                    val written = session.openRead(name).use { input -> sha256(input) }
                    if (!written.equals(apk.sha256, ignoreCase = true)) {
                        session.abandon()
                        return@withContext InstallStart.Failed(InstallStart.REASON_CONTENT_MISMATCH)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            InstallStart.Committed(sessionId)
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            InstallStart.Failed(e.javaClass.simpleName)
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** A PackageInstaller eredményének fogadása (nem exportált; docs/ARCHITECTURE.md 11.1.). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val event = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallEvent.Success(sessionId, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.intentExtra()
                if (confirm != null) {
                    InstallEvent.PendingUserAction(sessionId, confirm)
                } else {
                    InstallEvent.Failure(sessionId, status, "Hiányzó jóváhagyási felület", null, null)
                }
            }
            else -> InstallEvent.Failure(
                sessionId = sessionId,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(300),
                developerVerificationReason = if (intent.hasExtra(EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON)) {
                    intent.getIntExtra(EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON, 0)
                } else {
                    null
                },
                explanationIntent = intent.intentExtra(),
            )
        }
        InstallEvents.emit(event)
    }

    private fun Intent.intentExtra(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private companion object {
        /** `PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` (API 36.1); régebbi rendszeren hiányzik. */
        const val EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON = "android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON"
    }
}
