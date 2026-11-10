// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderId

/** Egy követett alkalmazás frissítési állapota az ellenőrzés után (C. 10.: „Frissítési állapot”). */
enum class UpdateStatus {
    /** Újabb kiadás érhető el (megerősítve vagy valószínűleg; lásd [VersionDecision.confirmed]). */
    UPDATE_AVAILABLE,
    UP_TO_DATE,

    /** Követett, de nincs telepítve (vagy nem látható); van telepíthető kiadás. */
    NOT_INSTALLED,

    /** A verzió nem állapítható meg (nem szabványos verziószámozás): letöltés után derül ki. */
    VERSION_UNKNOWN,
    NO_INSTALLABLE_RELEASE,

    /** A legújabb kiadás az előzetes becslés szerint nem telepíthető erre az eszközre. */
    INCOMPATIBLE,

    /** A kiadás állományát korábban már letöltöttük, és a vizsgálat elutasította: nem ajánljuk fel újra (ARCH 11.1.). */
    UPDATE_BLOCKED,
    REPO_NOT_FOUND,
    REPO_UNAVAILABLE,

    /** Azonos néven más repó: a frissítés tiltva, amíg a felhasználó nem dönt (C. 19.). */
    REPO_ID_CHANGED,

    /** Az API-keret miatt most nem ellenőrizhető (C. 12.1.). */
    RATE_LIMITED,
    ERROR,
}

data class UpdateCheck(
    val status: UpdateStatus,
    val discovery: Discovery?,
    val decision: VersionDecision?,
    val compatibility: CompatibilityAssessment?,
    val findings: List<Finding>,
    val failure: UpdateFailure?,
)

data class UpdateCheckInput(
    val source: TrackedSource,
    val installed: InstalledVersion?,
    val assetFilter: String?,
    val device: DeviceProfile,
)

/** Egy korábban letöltött és elemzett állomány eredménye (docs/ARCHITECTURE.md 9. pont, `ApkAnalysis`). */
data class KnownAnalysis(val versionCode: Long?, val rejected: Boolean)

/** A korábbi elemzések assetenként: így ugyanazt az állományt nem kell újra letölteni. */
interface KnownAnalyses {
    suspend fun of(provider: ProviderId, assetId: Long): KnownAnalysis?

    companion object {
        val NONE: KnownAnalyses = object : KnownAnalyses {
            override suspend fun of(provider: ProviderId, assetId: Long): KnownAnalysis? = null
        }
    }
}

/**
 * Frissítésellenőrzés letöltés nélkül: az 1–3. lépés egymás után (keresés → verzió → kompatibilitás).
 * A telepítés (4–8. lépés) mindig külön, a felhasználó indítja (C. 12.5.: az automatikus mód az 5. munkamenetben jön).
 */
class UpdateChecker(private val discovery: ReleaseDiscovery, private val known: KnownAnalyses) {

    suspend fun check(input: UpdateCheckInput): UpdateCheck {
        val found = when (val result = discovery.discover(input.source)) {
            is StepResult.Blocked -> {
                val status = if (result.findings.any { it.kind == FindingKind.REPO_ID_CHANGED }) UpdateStatus.REPO_ID_CHANGED else UpdateStatus.ERROR
                return UpdateCheck(status, null, null, null, result.findings, null)
            }
            is StepResult.Failed -> {
                val status = when (result.failure) {
                    is UpdateFailure.RateLimited -> UpdateStatus.RATE_LIMITED
                    UpdateFailure.RepoNotFound -> UpdateStatus.REPO_NOT_FOUND
                    is UpdateFailure.RepoUnavailable -> UpdateStatus.REPO_UNAVAILABLE
                    else -> UpdateStatus.ERROR
                }
                return UpdateCheck(status, null, null, null, result.findings, result.failure)
            }
            is StepResult.Ok -> result
        }
        return evaluate(found.value, found.findings, input.installed, input.assetFilter, input.device)
    }

    /** A 2–3. lépés egy már lekért kiadáslistán (hálózati kérés nélkül). */
    suspend fun evaluate(
        discovered: Discovery,
        findings: List<Finding>,
        installed: InstalledVersion?,
        assetFilter: String?,
        device: DeviceProfile,
    ): UpdateCheck {
        val candidate = discovered.candidate
            ?: return UpdateCheck(UpdateStatus.NO_INSTALLABLE_RELEASE, discovered, null, null, findings, null)

        val compatibility = CompatibilityAssessor.assess(candidate, device, assetFilter)
        val primaryAsset = when (val selection = compatibility.selection) {
            is AssetSelection.Single -> selection.asset
            is AssetSelection.SplitSet -> selection.base
            else -> null
        }
        val analysis = primaryAsset?.let { known.of(discovered.repo.ref.provider, it.id) }
        val decision = VersionResolver.resolve(candidate, analysis?.versionCode, installed)
        val status = when {
            decision.verdict == VersionVerdict.SAME || decision.verdict == VersionVerdict.OLDER -> UpdateStatus.UP_TO_DATE
            !compatibility.installable -> UpdateStatus.INCOMPATIBLE
            analysis?.rejected == true -> UpdateStatus.UPDATE_BLOCKED
            decision.verdict == VersionVerdict.NEWER -> UpdateStatus.UPDATE_AVAILABLE
            decision.verdict == VersionVerdict.NOT_INSTALLED -> UpdateStatus.NOT_INSTALLED
            else -> UpdateStatus.VERSION_UNKNOWN
        }
        return UpdateCheck(status, discovered, decision, compatibility, findings, null)
    }
}
