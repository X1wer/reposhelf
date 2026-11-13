// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.AssetRejection
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity

/** A C. 20. eseteinek kategóriái (a felület ezek szerint csoportosít és fogalmaz). */
enum class WarningCategory {
    /** Egy korábban követett repó eltűnt vagy nem érhető el. */
    REPO_GONE,

    /** A repó azonosítója váratlanul megváltozott (azonos név, más repó). */
    REPO_ID_CHANGED,

    /** A repó tulajdonosa megváltozott. */
    REPO_OWNER_CHANGED,

    /** Megváltozott az alkalmazás aláírója. */
    SIGNER_CHANGED,

    /** A frissítés más csomagazonosítót tartalmaz. */
    PACKAGE_CHANGED,

    /** Az új verzió régebbi verziókóddal rendelkezik. */
    DOWNGRADE,

    /** A letöltött állomány sérült (méret, ellenőrzőösszeg, szerkezet, aláírás). */
    CORRUPTED,

    /** Az alkalmazás inkompatibilis a készülékkel. */
    INCOMPATIBLE,

    /** A kiadási állomány nem a várt formátumú (pl. csak AAB). */
    UNEXPECTED_FORMAT,

    /** A forrás hitelessége nem ellenőrizhető (első találkozás, nincs rögzített aláíró). */
    UNVERIFIABLE_SOURCE,
}

/** Egy rögzítendő biztonsági figyelmeztetés (C. 20.). A [detail] nem tartalmaz érzékeny adatot. */
data class WarningSpec(val category: WarningCategory, val severity: Severity, val detail: String?) {
    /** Deduplikációs kulcs egy alkalmazáson belül: ugyanaz az eset nem kerül be újra, csak frissül (C. 20.). */
    val dedupSuffix: String get() = "${category.name}:${detail.orEmpty().take(120)}"
}

/**
 * A megállapítások besorolása a C. 20. eseteire, súlyossággal (info / figyelmeztetés / kritikus).
 *
 * A „ne legyen indokolatlanul gyakori vagy félrevezető” elv szerint:
 * - a forrás nem ellenőrizhető hitelessége (első telepítés) csak INFO, nem riasztás;
 * - a tájékoztató átnevezés nem figyelmeztetés;
 * - egy esetből egy figyelmeztetés lesz (a több, ugyanarra utaló megállapítás összevonva).
 */
object WarningClassifier {

    fun fromFindings(findings: List<Finding>): List<WarningSpec> {
        val result = LinkedHashMap<WarningCategory, WarningSpec>()
        for (finding in findings) {
            val (category, severity) = when (finding.kind) {
                FindingKind.REPO_NOT_FOUND, FindingKind.REPO_UNAVAILABLE -> WarningCategory.REPO_GONE to Severity.WARNING
                FindingKind.REPO_ID_CHANGED -> WarningCategory.REPO_ID_CHANGED to Severity.CRITICAL
                FindingKind.REPO_OWNER_CHANGED -> WarningCategory.REPO_OWNER_CHANGED to Severity.WARNING
                FindingKind.SIGNER_CHANGED, FindingKind.SIGNER_MISMATCH_PINNED, FindingKind.MULTIPLE_SIGNERS -> WarningCategory.SIGNER_CHANGED to Severity.CRITICAL
                FindingKind.PACKAGE_MISMATCH -> WarningCategory.PACKAGE_CHANGED to Severity.CRITICAL
                FindingKind.VERSION_DOWNGRADE, FindingKind.BELOW_MIN_VERSION -> WarningCategory.DOWNGRADE to Severity.CRITICAL
                FindingKind.SIZE_MISMATCH, FindingKind.DIGEST_MISMATCH, FindingKind.INVALID_APK, FindingKind.INVALID_SIGNATURE,
                FindingKind.SPLIT_SET_INCONSISTENT, FindingKind.INSTALLED_STATE_MISMATCH -> WarningCategory.CORRUPTED to Severity.CRITICAL
                FindingKind.INCOMPATIBLE_SDK, FindingKind.INCOMPATIBLE_ABI, FindingKind.TARGET_SDK_TOO_OLD -> WarningCategory.INCOMPATIBLE to Severity.INFO
                FindingKind.UNEXPECTED_FORMAT -> WarningCategory.UNEXPECTED_FORMAT to Severity.INFO
                FindingKind.FIRST_SEEN_SIGNER, FindingKind.UNVERIFIABLE_SOURCE -> WarningCategory.UNVERIFIABLE_SOURCE to Severity.INFO
                // Tájékoztató vagy normál állapot, nem biztonsági figyelmeztetés.
                FindingKind.REPO_RENAMED, FindingKind.NO_PROVIDER_DIGEST, FindingKind.SAME_VERSION, FindingKind.PRERELEASE,
                FindingKind.NO_INSTALLABLE_RELEASE -> continue
            }
            val existing = result[category]
            // Ugyanazon eseten belül a súlyosabb marad.
            if (existing == null || severity > existing.severity) result[category] = WarningSpec(category, severity, finding.detail?.take(200))
        }
        return result.values.toList()
    }

    /** Egy frissítésellenőrzés eredménye (repó-állapot, inkompatibilitás, váratlan formátum). */
    fun fromCheck(check: UpdateCheck): List<WarningSpec> {
        val specs = fromFindings(check.findings).toMutableList()
        if (check.status == UpdateStatus.INCOMPATIBLE && specs.none { it.category == WarningCategory.INCOMPATIBLE }) {
            specs += WarningSpec(WarningCategory.INCOMPATIBLE, Severity.INFO, check.discovery?.candidate?.tagName)
        }
        val rejected = (check.compatibility?.selection as? AssetSelection.NoInstallable)?.rejected.orEmpty()
        val formatRejected = rejected.filter { it.reason == AssetRejection.APP_BUNDLE || it.reason == AssetRejection.SPLIT_ARCHIVE || it.reason == AssetRejection.SPLIT_SET }
        if (formatRejected.isNotEmpty()) {
            specs += WarningSpec(WarningCategory.UNEXPECTED_FORMAT, Severity.INFO, formatRejected.joinToString { it.asset.name }.take(200))
        }
        return specs
    }

    /** A letöltött állomány tényleges vizsgálata (5–6. lépés). */
    fun fromReport(report: VerificationReport): List<WarningSpec> = fromFindings(report.findings)

    /**
     * A kiadáshoz kötött esetek (aláíró, csomagnév, verziókód, sérült állomány), amelyeket egy újabb, az ellenőrzésen
     * HIBÁTLANUL átment állomány megszüntet. Így egy elutasított kiadás nem tiltja örökre az automatikus telepítést;
     * az elutasított kiadás maga továbbra sem települ (az elemzése elutasítottként megmarad).
     */
    fun resolvedByReport(report: VerificationReport): Set<WarningCategory> {
        if (!report.isInstallable) return emptySet()
        // Az azonos verziójú állomány (pl. a már telepített APK újabb címkével közzétéve) semmit nem bizonyít: a
        // kiadásokat a repó birtokosa (kompromittált fióknál a támadó) adja meg, ezért ez nem oldhat fel riasztást.
        if (report.findings.any { it.kind == FindingKind.SAME_VERSION }) return emptySet()
        val present = fromReport(report).map { it.category }.toSet()
        // Az aláíróra, a csomagnévre és a verziókódra vonatkozó esetet csak IGAZOLT azonosságú állomány oldja fel
        // (a telepített példánnyal vagy a rögzített aláíróval egyező); a sérült letöltést bármely ép állomány.
        val identityProven = report.identity == SignerIdentity.MATCHES_INSTALLED || report.identity == SignerIdentity.MATCHES_PINNED
        return RELEASE_BOUND.filter { it !in present && (it == WarningCategory.CORRUPTED || identityProven) }.toSet()
    }

    private val RELEASE_BOUND = listOf(WarningCategory.SIGNER_CHANGED, WarningCategory.PACKAGE_CHANGED, WarningCategory.DOWNGRADE, WarningCategory.CORRUPTED)

    /** A kategóriák, amelyek az adott ellenőrzés után már nem állnak fenn, ha az ellenőrzés sikeres volt (feloldás). */
    fun resolvedByCheck(check: UpdateCheck): Set<WarningCategory> = when (check.status) {
        UpdateStatus.RATE_LIMITED, UpdateStatus.ERROR -> emptySet()
        else -> buildSet {
            val present = fromCheck(check).map { it.category }.toSet()
            listOf(WarningCategory.REPO_GONE, WarningCategory.REPO_ID_CHANGED, WarningCategory.INCOMPATIBLE, WarningCategory.UNEXPECTED_FORMAT)
                .filterNot { it in present }
                .forEach { add(it) }
        }
    }
}
