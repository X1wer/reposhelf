// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.ApkInspection
import io.github.x1wer.reposhelf.core.apk.ApkInspector
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.SignerInfo
import io.github.x1wer.reposhelf.core.update.InstallSource
import io.github.x1wer.reposhelf.core.update.InstalledAppLookup
import java.io.File
import java.security.MessageDigest

internal fun Signature.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

private fun PackageInfo.signerInfo(): SignerInfo? {
    val signing = signingInfo ?: return null
    return if (signing.hasMultipleSigners()) {
        val current = signing.apkContentsSigners.map { it.sha256() }
        SignerInfo(current, current, multipleSigners = true)
    } else {
        // A rotációs lánc a legrégebbitől az aktuálisig; az aktuális aláíró a lánc vége.
        val history = signing.signingCertificateHistory?.map { it.sha256() }.orEmpty()
        val current = history.lastOrNull()?.let(::listOf) ?: signing.apkContentsSigners.map { it.sha256() }
        SignerInfo(current, history.ifEmpty { current }, multipleSigners = false)
    }
}

/**
 * A `getPackageArchiveInfo` jelzői. Android 9-en (API 28, emulátoron ellenőrizve) a nem telepített APK tanúsítványait a
 * rendszer CSAK a régi `GET_SIGNATURES` jelzőre gyűjti be és ellenőrzi; a `GET_SIGNING_CERTIFICATES` önmagában üres
 * `signingInfo`-t ad, így minden érvényes APK-t elutasítanánk (D-071). Ezért mindkét jelzőt átadjuk: a
 * `GET_SIGNATURES` ellenőrzött módon (`skipVerify = false`) gyűjti be a tanúsítványokat, a kiértékelés viszont kizárólag
 * a `signingInfo`-ra épül (a régi `signatures` mezőt nem használjuk).
 */
@Suppress("DEPRECATION")
private const val ARCHIVE_SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

/**
 * Az APK ellenőrzése a készülék SAJÁT platform-verifierjével (D-031): a `getPackageArchiveInfo` a tanúsítványok
 * begyűjtésekor valódi kriptográfiai ellenőrzést végez, ugyanazokkal a szabályokkal, mint a telepítés.
 * Érvénytelen vagy nem ellenőrizhető APK-ra `null`-t ad, `signingInfo` nélkül pedig nem fogadjuk el.
 *
 * Ismert korlát: az elemzés jelenleg az alkalmazás folyamatán belül fut. A külön folyamatban, időkorláttal történő
 * futtatás (D-031) a 8. munkamenet (biztonsági audit) feladata.
 */
class PlatformApkInspector(private val context: Context) : ApkInspector {
    override fun inspect(file: File): ApkInspection {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(ARCHIVE_SIGNING_FLAGS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.path, ARCHIVE_SIGNING_FLAGS)
            }
        } catch (_: RuntimeException) {
            null
        } ?: return ApkInspection.Invalid("A rendszer nem tudta érvényes, aláírt APK-ként feldolgozni")
        val app = info.applicationInfo ?: return ApkInspection.Invalid("Hiányzó alkalmazásadatok")
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        val signers = info.signerInfo() ?: return ApkInspection.Invalid("Nincs aláírási információ")
        return ApkInspection.Valid(
            ApkFacts(
                packageName = info.packageName,
                versionCode = info.longVersionCode,
                versionName = info.versionName,
                minSdk = app.minSdkVersion,
                targetSdk = app.targetSdkVersion,
                splitName = info.splitNames?.singleOrNull(),
                signers = signers,
            ),
        )
    }
}

/** A készüléken telepített csomagok lekérdezése (csak a láthatók; docs/ARCHITECTURE.md 13.). */
class InstalledPackages(private val context: Context) : InstalledAppLookup {
    override fun get(packageName: String): InstalledAppInfo? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        InstalledAppInfo(info.packageName, info.longVersionCode, info.versionName, info.signerInfo()?.history.orEmpty())
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun launchIntent(packageName: String): Intent? = context.packageManager.getLaunchIntentForPackage(packageName)

    /**
     * A telepített csomag telepítési forrása: az installer of record és (Android 14+) az update owner. A felhasználói
     * beavatkozás nélküli frissítés feltétele (ARCH 11.2.). Nem látható csomagnál `null`.
     */
    fun installSource(packageName: String): InstallSource? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(packageName)
            val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.updateOwnerPackageName else null
            InstallSource(info.installingPackageName, owner)
        } else {
            @Suppress("DEPRECATION")
            InstallSource(pm.getInstallerPackageName(packageName), null)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** A telepített alkalmazás ikonja (csak látható csomagnál; ARCH 13.). */
    fun icon(packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Az eltávolítás kezdeményezése: a rendszer saját megerősítő ablaka jelenik meg (C. 10.). A felhasználó dönt;
     * a mi oldalunkon semmi nem törlődik automatikusan. Jogosultság: `REQUEST_DELETE_PACKAGES` (normál, API 28+).
     */
    fun uninstallIntent(packageName: String): Intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))

    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /** Ad-e engedélyt a felhasználó, hogy ez az app telepíthessen (Android 8+: alkalmazásonkénti beállítás). */
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** A rendszer beállítóképernyője, ahol a felhasználó az „ismeretlen források” engedélyt megadhatja. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun deviceProfile(): DeviceProfile {
        val free = runCatching { StatFs(context.noBackupFilesDir.path).availableBytes }.getOrNull()
        return DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), free)
    }
}
