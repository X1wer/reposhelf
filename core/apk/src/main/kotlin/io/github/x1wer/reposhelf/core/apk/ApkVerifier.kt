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
    /**
     * A MEGBÍZHATÓ FORRÁSBAN rögzített aláíró-ujjlenyomatok: a katalógus (aláírt) `signingCertSha256` listája, saját
     * frissítésnél a buildbe fordított aláíró. Ha nem üres, legalább az egyiknek szerepelnie kell az APK ellenőrzött
     * láncában; ezt a helyben rögzített aláíró ([storedSignerCerts]) egyezése NEM pótolja.
     */
    val pinnedSignerCerts: List<String>,
    val minVersionCode: Long?,
    val installed: InstalledAppInfo?,
    val device: DeviceProfile,
    /**
     * A készüléken korábban rögzített aláíró (első telepítéskor – trust on first use –, vagy a már telepített
     * példányról), a rotációs lánc sorrendjében: az UTOLSÓ elem a legutóbb érvényes aláíró. Ennek kell szerepelnie az
     * APK ellenőrzött láncában: egy rotációval lecserélt (visszavont) régi kulcs önmagában nem elég.
     */
    val storedSignerCerts: List<String> = emptyList(),
    /**
     * Split-készletnél a kiegészítő állományok (az első utániak) ellenőrzése a rendszerre bízható-e (D-128): a rendszer
     * elemzője az önálló splitet nem dolgozza fel, a telepítéskor viszont elutasítja a más csomagú, verziójú vagy az
     * alap-APK-étól eltérő aláírójú splitet. Az alap-APK ettől függetlenül mindig teljes vizsgálaton megy át.
     * Alapértelmezés: nem (a saját frissítés csak önálló, teljesen ellenőrzött APK-t fogad el).
     */
    val allowSystemVerifiedSplits: Boolean = false,
    /**
     * A csomag ezen a készüléken valaha látott legnagyobb verziókódja (a rögzített aláíró mellett tárolva, SIG-07).
     * Csak akkor számít, ha a csomag most nincs telepítve: a régebbi állomány ilyenkor figyelmeztetést kap.
     */
    val previouslyInstalledVersionCode: Long? = null,
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
    /**
     * Az összes állomány adatai a bemenet sorrendjében (csak teljes, sikeres vizsgálat után). `null` = a rendszerre
     * bízott ellenőrzésű split (D-128).
     */
    val fileFacts: List<ApkFacts?> = emptyList(),
    /** Hány kiegészítő állomány (split) ellenőrzését végzi csak a rendszer a telepítéskor (D-128). */
    val systemVerifiedSplits: Int = 0,
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

        // 1. szint: technikai érvényesség (az eszköz saját verifierje). Split-készletnél az ELSŐ állomány az alap-APK (a
        // választás sorrendje), a többi a kiegészítő állomány.
        val inspected = arrayOfNulls<ApkFacts>(files.size)
        var signatureValid = CheckStatus.OK
        var systemVerified = 0
        for ((index, file) in files.withIndex()) {
            when (val result = inspector.inspect(file.file)) {
                is ApkInspection.Valid -> inspected[index] = result.facts
                is ApkInspection.Invalid -> if (index > 0 && context.allowSystemVerifiedSplits) {
                    // A rendszer elemzője az önálló splitet nem dolgozza fel; a csomagnevét, a verziókódját és az alap-APK-éval
                    // pontosan egyező aláíróját a rendszer ellenőrzi a telepítéskor, eltérésnél az egész készletet
                    // elutasítja (D-128). A sértetlenséget (méret, SHA-256, ZIP-szerkezet) az 5. lépés már ellenőrizte.
                    systemVerified++
                } else {
                    signatureValid = CheckStatus.FAILED
                    findings += Finding(FindingKind.INVALID_SIGNATURE, Severity.CRITICAL, result.reason)
                }
            }
        }
        val fileFacts = inspected.toList()
        val base = fileFacts.first()
        val splits = fileFacts.drop(1).filterNotNull()
        if (findings.any { it.severity == Severity.CRITICAL } || base == null) {
            return VerificationReport(integrity.items, signatureValid, SignerIdentity.NOT_CHECKED, emptyList(), null, splits, nativeAbis, findings)
        }
        val integrityItems = integrity.items

        val compatibility = mutableListOf<CheckItem>()
        if (base.splitName != null) {
            // Az első állomány nem önálló alap-APK (pl. egyetlen, önmagában nem telepíthető split).
            compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.FAILED)
            findings += Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL, "alap-APK nélküli split")
            return VerificationReport(integrityItems, signatureValid, SignerIdentity.NOT_CHECKED, compatibility, null, splits, nativeAbis, findings)
        }
        if (files.size > 1) {
            // Ahol a rendszer a splitet feldolgozza (pl. tesztkörnyezet), ott mi is összevetjük az alap-APK-val.
            val consistent = splits.all {
                it.splitName != null && it.packageName == base.packageName && it.versionCode == base.versionCode && it.signers.current == base.signers.current
            } && splits.map { it.splitName }.distinct().size == splits.size
            if (!consistent) {
                compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.FAILED)
                findings += Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL)
                return VerificationReport(integrityItems, signatureValid, SignerIdentity.NOT_CHECKED, compatibility, base, splits, nativeAbis, findings)
            }
            if (systemVerified > 0) {
                compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.WARNING, systemVerified.toString())
                findings += Finding(FindingKind.SPLITS_VERIFIED_BY_SYSTEM, Severity.WARNING, systemVerified.toString())
            } else {
                compatibility += CheckItem(Check.SPLIT_SET, CheckStatus.OK, splits.joinToString { it.splitName.orEmpty() })
            }
        }
        val facts = base

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
            installed == null && context.previouslyInstalledVersionCode != null && facts.versionCode < context.previouslyInstalledVersionCode -> {
                compatibility += CheckItem(Check.VERSION_CODE, CheckStatus.WARNING, "${facts.versionCode} < ${context.previouslyInstalledVersionCode}")
                findings += Finding(FindingKind.OLDER_THAN_PREVIOUS_INSTALL, Severity.WARNING, context.previouslyInstalledVersionCode.toString())
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

        return VerificationReport(integrityItems, signatureValid, identity, compatibility, facts, splits, nativeAbis, findings, fileFacts, systemVerified)
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
        // Minden rendelkezésre álló forrásnak külön-külön teljesülnie kell: a telepített példány, a megbízható forrás
        // (katalógus / build) és a helyben rögzített aláíró. Egyik egyezése sem pótolja a másik eltérését.
        val installed = context.installed
        if (installed != null) {
            val installedCurrent = installed.signingHistory.lastOrNull()?.lowercase()
            if (installedCurrent == null) {
                // A telepített példány aláírója nem olvasható ki: a folytonosság nem igazolható (soha nem „első látás”).
                findings += Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL, "a telepített példány aláírója nem olvasható ki")
                return SignerIdentity.MISMATCH
            }
            if (installedCurrent in history) {
                result = SignerIdentity.MATCHES_INSTALLED
            } else {
                findings += Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)
                return SignerIdentity.MISMATCH
            }
        }
        val trusted = context.pinnedSignerCerts.map { it.lowercase() }
        if (trusted.isNotEmpty()) {
            if (trusted.none { it in history }) {
                findings += Finding(FindingKind.SIGNER_MISMATCH_PINNED, Severity.CRITICAL, "katalógus")
                return SignerIdentity.MISMATCH
            }
            if (result == SignerIdentity.FIRST_SEEN) result = SignerIdentity.MATCHES_PINNED
        }
        val storedCurrent = context.storedSignerCerts.lastOrNull()?.lowercase()
        if (storedCurrent != null) {
            if (storedCurrent !in history) {
                findings += Finding(FindingKind.SIGNER_MISMATCH_PINNED, Severity.CRITICAL, "korábban rögzített aláíró")
                return SignerIdentity.MISMATCH
            }
            if (result == SignerIdentity.FIRST_SEEN) result = SignerIdentity.MATCHES_PINNED
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
