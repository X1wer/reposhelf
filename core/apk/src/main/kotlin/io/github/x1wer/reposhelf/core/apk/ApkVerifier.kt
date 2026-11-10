// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import java.io.File

/** Az eszköz jellemzői a kompatibilitásvizsgálathoz. */
data class DeviceProfile(val sdkInt: Int, val supportedAbis: List<String>, val freeBytes: Long?)

/** A készüléken telepített példány adatai (ha a csomag látható, docs/ARCHITECTURE.md 13.). */
data class InstalledAppInfo(val packageName: String, val versionCode: Long, val versionName: String?, val signingHistory: List<String>)

/** Egy letöltött állomány a hozzá tartozó, letöltéskor számolt adatokkal. */
data class DownloadedApk(
    val file: File,
    val sha256: String,
    val size: Long,
    val expectedSize: Long,
    /** A szolgáltató által közölt SHA-256 (GitHub `digest`), ha van. */
    val providerSha256: String?,
)

/** Mi alapján várunk el valamit a letöltött APK-tól. */
data class VerificationContext(
    val expectedPackageName: String?,
    /** Rögzített aláíró-ujjlenyomatok: a katalógusból vagy korábbi telepítésből (trust on first use). */
    val pinnedSignerCerts: List<String>,
    val minVersionCode: Long?,
    val installed: InstalledAppInfo?,
    val device: DeviceProfile,
)

enum class Check { SIZE, SHA256, PROVIDER_DIGEST, APK_STRUCTURE, SIGNATURE, SIGNER_IDENTITY, PACKAGE_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, ABI, SPLIT_SET }

enum class CheckStatus { OK, WARNING, FAILED, NOT_CHECKED }

data class CheckItem(val check: Check, val status: CheckStatus, val detail: String? = null)

/** Az aláíró azonosságának eredménye (2. szint, C. 18.). */
enum class SignerIdentity {
    /** Az aláíró megegyezik a telepített alkalmazás aláírójával (vagy annak érvényes rotációja). */
    MATCHES_INSTALLED,

    /** Az aláíró megegyezik a rögzített (katalógusbeli vagy korábbi) ujjlenyomattal. */
    MATCHES_PINNED,

    /** Nincs összehasonlítási alap: az aláíró most kerül rögzítésre. */
    FIRST_SEEN,

    /** Eltér, nincs érvényes rotációs lánc. */
    MISMATCH,

    /** Nem ellenőrizhető (pl. az 1. szint nem teljesült). */
    NOT_CHECKED,
}

/**
 * A letöltött APK tényleges vizsgálatának eredménye (C. 7.1., 18.). A három aláírási szint külön szerepel:
 * - 1. technikai érvényesség: [signatureValid];
 * - 2. az aláíró azonossága a korábbi ismert állapothoz képest: [identity];
 * - 3. a kiadó megbízhatósága: ezt nem lehet kriptográfiailag eldönteni, ezért itt csak a tények szerepelnek
 *   ([Finding] `UNVERIFIABLE_SOURCE` / `FIRST_SEEN_SIGNER`); az értékelést a felület nem állítja „biztonságosnak”.
 */
data class VerificationReport(
    val integrity: List<CheckItem>,
    val signatureValid: CheckStatus,
    val identity: SignerIdentity,
    val compatibility: List<CheckItem>,
    val facts: ApkFacts?,
    val splitFacts: List<ApkFacts>,
    val nativeAbis: Set<String>,
    val findings: List<Finding>,
    /** Az összes állomány adatai a bemenet sorrendjében (csak teljes, sikeres vizsgálat után). */
    val fileFacts: List<ApkFacts> = emptyList(),
) {
    val isInstallable: Boolean get() = facts != null && findings.none { it.severity == Severity.CRITICAL }

    /** Az aktuális aláíró(k) ujjlenyomata – ezt rögzítjük első telepítés után. */
    val signerCertSha256: List<String> get() = facts?.signers?.current.orEmpty()
}

/** Az 5. lépés (integritás) eredménye: méret, SHA-256, a szolgáltató ellenőrzőösszege, ZIP-szerkezet. */
data class IntegrityReport(val items: List<CheckItem>, val findings: List<Finding>, val nativeAbis: Set<String>) {
    val passed: Boolean get() = findings.none { it.severity == Severity.CRITICAL }
}

/**
 * A letöltött APK (vagy split-készlet) tényleges ellenőrzése (docs/ARCHITECTURE.md 9. pont, 5–6. lépés és a 7. lépés
 * előfeltételei). Tiszta logika: a kriptográfiai munkát az [ApkInspector] végzi.
 *
 * A két lépés külön is hívható és tesztelhető (C. 34.): [checkIntegrity] (5.) és [checkSignature] (6.).
 */
class ApkVerifier(private val inspector: ApkInspector) {

    fun verify(files: List<DownloadedApk>, context: VerificationContext): VerificationReport =
        checkSignature(files, checkIntegrity(files), context)

    /** 5. lépés: integritás. Kicsomagolás nélkül, csak a fájlok bájtjain és a ZIP központi könyvtárán dolgozik. */
    fun checkIntegrity(files: List<DownloadedApk>): IntegrityReport {
        require(files.isNotEmpty())
        val findings = mutableListOf<Finding>()
        val integrity = mutableListOf<CheckItem>()
        val nativeAbis = LinkedHashSet<String>()
        for (file in files) {
            if (file.size != file.expectedSize || file.file.length() != file.expectedSize) {
                integrity += CheckItem(Check.SIZE, CheckStatus.FAILED, "${file.size} / ${file.expectedSize} bájt")
                findings += Finding(FindingKind.SIZE_MISMATCH, Severity.CRITICAL, file.file.name)
            } else {
                integrity += CheckItem(Check.SIZE, CheckStatus.OK)
            }
            integrity += CheckItem(Check.SHA256, CheckStatus.OK, file.sha256)
            when {
                file.providerSha256 == null -> {
                    integrity += CheckItem(Check.PROVIDER_DIGEST, CheckStatus.NOT_CHECKED)
                    findings += Finding(FindingKind.NO_PROVIDER_DIGEST, Severity.INFO)
                }
                file.providerSha256.equals(file.sha256, ignoreCase = true) -> integrity += CheckItem(Check.PROVIDER_DIGEST, CheckStatus.OK)
                else -> {
                    integrity += CheckItem(Check.PROVIDER_DIGEST, CheckStatus.FAILED)
                    findings += Finding(FindingKind.DIGEST_MISMATCH, Severity.CRITICAL, file.file.name)
                }
            }
            val zip = ZipInspector.inspect(file.file)
            when {
                zip == null || !zip.hasManifest -> {
                    integrity += CheckItem(Check.APK_STRUCTURE, CheckStatus.FAILED, file.file.name)
                    findings += Finding(FindingKind.INVALID_APK, Severity.CRITICAL, file.file.name)
                }
                zip.suspiciousEntry != null -> {
                    integrity += CheckItem(Check.APK_STRUCTURE, CheckStatus.FAILED, zip.suspiciousEntry)
                    findings += Finding(FindingKind.INVALID_APK, Severity.CRITICAL, zip.suspiciousEntry)
                }
                else -> {
                    integrity += CheckItem(Check.APK_STRUCTURE, CheckStatus.OK)
                    nativeAbis += zip.nativeAbis
                }
            }
        }
        return IntegrityReport(integrity, findings, nativeAbis)
    }

    /**
     * 6. lépés: az aláírás három szintje (C. 18.) és a csomag tényleges adatainak összevetése a várttal.
     * Csak sértetlen fájlokon fut: ha az [integrity] nem ment át, az aláírás „nem ellenőrzött”.
     */
    fun checkSignature(files: List<DownloadedApk>, integrity: IntegrityReport, context: VerificationContext): VerificationReport {
        require(files.isNotEmpty())
        val findings = integrity.findings.toMutableList()
        val nativeAbis = integrity.nativeAbis
        if (!integrity.passed) {
            return VerificationReport(integrity.items, CheckStatus.NOT_CHECKED, SignerIdentity.NOT_CHECKED, emptyList(), null, emptyList(), nativeAbis, findings)
        }

        // 1. szint: technikai érvényesség (az eszköz saját verifierje).
        val inspected = mutableListOf<ApkFacts>()
        var signatureValid = CheckStatus.OK
        for (file in files) {
            when (val result = inspector.inspect(file.file)) {
                is ApkInspection.Invalid -> {
                    signatureValid = CheckStatus.FAILED
                    findings += Finding(FindingKind.INVALID_SIGNATURE, Severity.CRITICAL, result.reason)
                }
                is ApkInspection.Valid -> inspected += result.facts
            }
        }
        if (findings.any { it.severity == Severity.CRITICAL } || inspected.size != files.size) {
            return VerificationReport(integrity.items, signatureValid, SignerIdentity.NOT_CHECKED, emptyList(), null, inspected, nativeAbis, findings)
        }
        val integrityItems = integrity.items

        val compatibility = mutableListOf<CheckItem>()
        val base = inspected.singleOrNull { it.splitName == null }
        val splits = inspected.filter { it.splitName != null }
        if (files.size > 1) {
            val consistent = base != null &&
                splits.all { it.packageName == base.packageName && it.versionCode == base.versionCode && it.signers.current == base.signers.current } &&
                splits.map { it.splitName }.distinct().size == splits.size
            if (!consistent) {
                compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.FAILED)
                findings += Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL)
                return VerificationReport(integrityItems, signatureValid, SignerIdentity.NOT_CHECKED, compatibility, base, splits, nativeAbis, findings)
            }
            compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.OK, splits.joinToString { it.splitName.orEmpty() })
        } else if (base == null) {
            // Egyetlen fájl, de split: önmagában nem telepíthető.
            findings += Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL, "alap-APK nélküli split")
            return VerificationReport(integrityItems, signatureValid, SignerIdentity.NOT_CHECKED, compatibility, null, splits, nativeAbis, findings)
        }
        val facts = checkNotNull(base)

        // Csomagazonosító.
        val expectedPackage = context.expectedPackageName ?: context.installed?.packageName
        if (expectedPackage != null && expectedPackage != facts.packageName) {
            compatibility += CheckItem(Check.PACKAGE_NAME, CheckStatus.FAILED, "${facts.packageName} ≠ $expectedPackage")
            findings += Finding(FindingKind.PACKAGE_MISMATCH, Severity.CRITICAL, facts.packageName)
        } else {
            compatibility += CheckItem(Check.PACKAGE_NAME, CheckStatus.OK, facts.packageName)
        }

        // Verziókód (a visszalépés tiltott).
        val installed = context.installed
        when {
            installed != null && facts.versionCode < installed.versionCode -> {
                compatibility += CheckItem(Check.VERSION_CODE, CheckStatus.FAILED, "${facts.versionCode} < ${installed.versionCode}")
                findings += Finding(FindingKind.VERSION_DOWNGRADE, Severity.CRITICAL)
            }
            context.minVersionCode != null && facts.versionCode < context.minVersionCode -> {
                compatibility += CheckItem(Check.VERSION_CODE, CheckStatus.FAILED, "${facts.versionCode} < ${context.minVersionCode}")
                findings += Finding(FindingKind.BELOW_MIN_VERSION, Severity.CRITICAL)
            }
            installed != null && facts.versionCode == installed.versionCode -> {
                compatibility += CheckItem(Check.VERSION_CODE, CheckStatus.WARNING, facts.versionCode.toString())
                findings += Finding(FindingKind.SAME_VERSION, Severity.WARNING)
            }
            else -> compatibility += CheckItem(Check.VERSION_CODE, CheckStatus.OK, facts.versionCode.toString())
        }

        // Android-verzió.
        if (facts.minSdk > context.device.sdkInt) {
            compatibility += CheckItem(Check.MIN_SDK, CheckStatus.FAILED, "${facts.minSdk} > ${context.device.sdkInt}")
            findings += Finding(FindingKind.INCOMPATIBLE_SDK, Severity.CRITICAL)
        } else {
            compatibility += CheckItem(Check.MIN_SDK, CheckStatus.OK, facts.minSdk.toString())
        }
        val minInstallableTarget = minimumInstallableTargetSdk(context.device.sdkInt)
        if (facts.targetSdk < minInstallableTarget) {
            compatibility += CheckItem(Check.TARGET_SDK, CheckStatus.FAILED, "${facts.targetSdk} < $minInstallableTarget")
            findings += Finding(FindingKind.TARGET_SDK_TOO_OLD, Severity.CRITICAL)
        } else {
            compatibility += CheckItem(Check.TARGET_SDK, CheckStatus.OK, facts.targetSdk.toString())
        }

        // CPU-architektúra (a natív könyvtárak alapján; ha nincs natív könyvtár, bármely ABI-n fut).
        val deviceAbis = context.device.supportedAbis.map(AbiDetector::normalize)
        val apkAbis = nativeAbis.map(AbiDetector::normalize)
        if (apkAbis.isNotEmpty() && apkAbis.none { it in deviceAbis }) {
            compatibility += CheckItem(Check.ABI, CheckStatus.FAILED, apkAbis.joinToString())
            findings += Finding(FindingKind.INCOMPATIBLE_ABI, Severity.CRITICAL)
        } else {
            compatibility += CheckItem(Check.ABI, CheckStatus.OK, apkAbis.joinToString().ifEmpty { null })
        }

        // 6. lépés, 2. szint: az aláíró azonossága.
        val identity = identity(facts.signers, context, findings)
        compatibility += CheckItem(
            Check.SIGNER_IDENTITY,
            when (identity) {
                SignerIdentity.MATCHES_INSTALLED, SignerIdentity.MATCHES_PINNED -> CheckStatus.OK
                SignerIdentity.FIRST_SEEN -> CheckStatus.WARNING
                SignerIdentity.MISMATCH -> CheckStatus.FAILED
                SignerIdentity.NOT_CHECKED -> CheckStatus.NOT_CHECKED
            },
            facts.signers.current.joinToString(),
        )

        return VerificationReport(integrityItems, signatureValid, identity, compatibility, facts, splits, nativeAbis, findings, inspected)
    }

    private fun identity(signers: SignerInfo, context: VerificationContext, findings: MutableList<Finding>): SignerIdentity {
        if (signers.multipleSigners) {
            findings += Finding(FindingKind.MULTIPLE_SIGNERS, Severity.CRITICAL)
            return SignerIdentity.MISMATCH
        }
        // A rögzített tanúsítványnak a teljes, ellenőrzött láncban kell szerepelnie, nem csak az aktuális aláírónál
        // (v3.1/v3.2 platformfüggés, docs/ARCHITECTURE.md 10.3.).
        val history = signers.history.ifEmpty { signers.current }.map { it.lowercase() }
        var result = SignerIdentity.FIRST_SEEN
        val installedCurrent = context.installed?.signingHistory?.lastOrNull()?.lowercase()
        if (installedCurrent != null) {
            if (installedCurrent in history) {
                result = SignerIdentity.MATCHES_INSTALLED
            } else {
                findings += Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)
                return SignerIdentity.MISMATCH
            }
        }
        val pins = context.pinnedSignerCerts.map { it.lowercase() }
        if (pins.isNotEmpty()) {
            if (pins.any { it in history }) {
                if (result == SignerIdentity.FIRST_SEEN) result = SignerIdentity.MATCHES_PINNED
            } else {
                findings += Finding(FindingKind.SIGNER_MISMATCH_PINNED, Severity.CRITICAL)
                return SignerIdentity.MISMATCH
            }
        }
        if (result == SignerIdentity.FIRST_SEEN) {
            findings += Finding(FindingKind.FIRST_SEEN_SIGNER, Severity.INFO)
            findings += Finding(FindingKind.UNVERIFIABLE_SOURCE, Severity.INFO)
        }
        return result
    }

    companion object {
        /** A rendszer által még telepíthető legkisebb targetSdk (Android 14: 23, Android 15+: 24; docs/RESEARCH.md §3). */
        fun minimumInstallableTargetSdk(deviceSdk: Int): Int = when {
            deviceSdk >= 35 -> 24
            deviceSdk >= 34 -> 23
            else -> 0
        }
    }
}
