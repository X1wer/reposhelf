// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.Severity
import java.time.Duration

/** Az automatikus frissítésellenőrzés gyakorisága (C. 12.2.). A pontos időpont nem garantált (Doze, App Standby). */
enum class CheckFrequency(val hours: Long?) {
    EVERY_6_HOURS(6),
    EVERY_12_HOURS(12),
    DAILY(24),
    WEEKLY(24 * 7),
    MANUAL(null),
}

/**
 * Globális beállítások. Az alapértékek a C. 12.5. szerinti biztonságos működés: az automatikus ellenőrzés be van
 * kapcsolva, az automatikus letöltés és telepítés KI, az értesítés a felhasználó hozzájárulásával (rendszerengedély).
 */
data class GlobalSettings(
    val checkFrequency: CheckFrequency = CheckFrequency.DAILY,
    /** Az ellenőrzés csak nem forgalomkorlátos (pl. Wi-Fi) hálózaton fusson. */
    val checkOnlyUnmetered: Boolean = false,
    val autoDownload: Boolean = false,
    /** Automatikus letöltés mobiladaton (forgalomkorlátos hálózaton) is (C. 12.3.). */
    val autoDownloadOnMetered: Boolean = false,
    val autoInstall: Boolean = false,
    val includePrereleases: Boolean = false,
    /** Új kiadások késleltetése napokban (C. 12.6.): a frissen megjelent kiadást csak ennyi idő után ajánljuk. */
    val releaseDelayDays: Int = 0,
    val notifyUpdates: Boolean = true,
    /** Update ownership kérése első telepítéskor (D-043). */
    val requestUpdateOwnership: Boolean = true,
) {
    companion object {
        val RELEASE_DELAY_CHOICES = listOf(0, 1, 3, 7)
    }
}

/** Alkalmazásonkénti felülírások; `null` = a globális beállítás érvényes (C. 12.6.). */
data class AppOverrides(
    val checkEnabled: Boolean? = null,
    val includePrereleases: Boolean? = null,
    val autoDownload: Boolean? = null,
    val autoInstall: Boolean? = null,
    val notify: Boolean? = null,
    val releaseDelayDays: Int? = null,
) {
    val isEmpty: Boolean get() = this == AppOverrides()
}

/** Egy alkalmazásra ténylegesen érvényes szabályok (a globális beállítás és a felülírás összevetése). */
data class AppPolicy(
    val checkEnabled: Boolean,
    val includePrereleases: Boolean,
    val autoDownload: Boolean,
    val autoInstall: Boolean,
    val notify: Boolean,
    val releaseDelay: Duration,
)

object PolicyResolver {
    fun resolve(global: GlobalSettings, overrides: AppOverrides?): AppPolicy {
        val o = overrides ?: AppOverrides()
        val autoInstall = o.autoInstall ?: global.autoInstall
        return AppPolicy(
            checkEnabled = o.checkEnabled ?: true,
            includePrereleases = o.includePrereleases ?: global.includePrereleases,
            // Telepíteni csak letöltött és ellenőrzött állományt lehet: az automatikus telepítés letöltéssel jár.
            autoDownload = (o.autoDownload ?: global.autoDownload) || autoInstall,
            autoInstall = autoInstall,
            notify = o.notify ?: global.notifyUpdates,
            releaseDelay = Duration.ofDays((o.releaseDelayDays ?: global.releaseDelayDays).coerceIn(0, MAX_DELAY_DAYS).toLong()),
        )
    }

    private const val MAX_DELAY_DAYS = 30
}

data class NetworkState(val connected: Boolean, val metered: Boolean)

/** Mit tegyen a háttérfeladat egy ellenőrzött alkalmazással. */
data class BackgroundPlan(val notify: Boolean, val download: Boolean, val install: Boolean) {
    companion object {
        val NOTHING = BackgroundPlan(notify = false, download = false, install = false)
    }
}

/** A háttérben futó ellenőrzés utáni döntés (C. 12.3–12.5.). Tiszta logika, a platformtól független. */
object BackgroundDecision {

    fun plan(check: UpdateCheck, policy: AppPolicy, global: GlobalSettings, network: NetworkState, installed: Boolean): BackgroundPlan {
        if (!policy.checkEnabled || !installed || check.status != UpdateStatus.UPDATE_AVAILABLE) return BackgroundPlan.NOTHING
        val determinate = check.compatibility?.selection.let { it is AssetSelection.Single || it is AssetSelection.SplitSet }
        // Mobiladaton csak a felhasználó kifejezett engedélyével töltünk le (C. 12.3.).
        val networkAllows = network.connected && (!network.metered || global.autoDownloadOnMetered)
        val download = policy.autoDownload && determinate && networkAllows
        return BackgroundPlan(notify = policy.notify, download = download, install = download && policy.autoInstall)
    }
}

/** Miért nem (vagy miért igen) telepíthető a frissítés felhasználói beavatkozás nélkül. */
enum class UnattendedReason {
    ALLOWED,
    DISABLED_BY_USER,

    /** Az első telepítés mindig jóváhagyásos. */
    FIRST_INSTALL,

    /** Android 12 (API 31) előtt nincs ilyen lehetőség. */
    ANDROID_TOO_OLD,

    /** Nem mi vagyunk a telepítő (installer of record), és update owner sem. */
    NOT_INSTALLER,

    /** Más telepítő az update owner (Android 14+). */
    OWNED_BY_OTHER,

    /** A frissítés targetSdk-ja a rendszer által megkövetelt küszöb alatt van. */
    TARGET_SDK_TOO_LOW,

    /** Az aláíró-folytonosság nem igazolt, vagy van kritikus megállapítás. */
    NOT_VERIFIED,
}

/** A telepített csomag telepítési forrása (`getInstallSourceInfo`). */
data class InstallSource(val installerPackage: String?, val updateOwnerPackage: String?)

/**
 * A felhasználói beavatkozás nélküli telepítés döntési logikája (docs/ARCHITECTURE.md 11.2., C. 12.4.). Csak akkor
 * kérjük a `USER_ACTION_NOT_REQUIRED` módot, ha minden feltétel teljesül. A rendszer ezután is kérhet jóváhagyást:
 * az normál működés, nem hiba.
 */
object UnattendedInstallPolicy {

    /**
     * A rendszer által megkövetelt legkisebb targetSdk Android-verziónként, a dokumentáció szigorúbb értékeivel
     * (API 31→29, 33→30, 34→31, 35→33, 36→34, 37→35). API 31 előtt nincs ilyen mód (`null`).
     */
    fun minimumTargetSdk(deviceSdk: Int): Int? = when {
        deviceSdk >= 37 -> 35
        deviceSdk == 36 -> 34
        deviceSdk == 35 -> 33
        deviceSdk == 34 -> 31
        deviceSdk == 33 -> 30
        deviceSdk >= 31 -> 29
        else -> null
    }

    fun evaluate(
        userEnabled: Boolean,
        deviceSdk: Int,
        ourPackage: String,
        installedSource: InstallSource?,
        report: VerificationReport,
    ): UnattendedReason {
        if (!userEnabled) return UnattendedReason.DISABLED_BY_USER
        val minTarget = minimumTargetSdk(deviceSdk) ?: return UnattendedReason.ANDROID_TOO_OLD
        val source = installedSource ?: return UnattendedReason.FIRST_INSTALL
        val facts = report.facts
        if (!report.isInstallable || facts == null || report.identity != SignerIdentity.MATCHES_INSTALLED ||
            report.findings.any { it.severity == Severity.CRITICAL }
        ) {
            return UnattendedReason.NOT_VERIFIED
        }
        val owner = source.updateOwnerPackage
        when {
            owner != null && owner != ourPackage -> return UnattendedReason.OWNED_BY_OTHER
            owner == null && source.installerPackage != ourPackage -> return UnattendedReason.NOT_INSTALLER
        }
        if (facts.targetSdk < minTarget) return UnattendedReason.TARGET_SDK_TOO_LOW
        return UnattendedReason.ALLOWED
    }
}
