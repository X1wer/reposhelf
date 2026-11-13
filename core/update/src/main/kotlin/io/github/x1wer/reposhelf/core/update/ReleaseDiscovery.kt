// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ReleaseResolver
import io.github.x1wer.reposhelf.core.apk.ReleaseResolver.date
import io.github.x1wer.reposhelf.core.apk.ResolvedReleases
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceError
import java.time.Clock
import java.time.Duration

/** Egy követett alkalmazás forrása, a korábban rögzített azonosítókkal (C. 19.). */
data class TrackedSource(
    val ref: RepoRef,
    /** A rögzített stabil repó-azonosító; `null`, ha még nincs (első ellenőrzés). */
    val repoId: ProviderRepoId?,
    /** A rögzített tulajdonos-azonosító; `null`, ha nem ismert. */
    val ownerId: Long?,
    val includePrereleases: Boolean,
    /** Új kiadások késleltetése (C. 12.6.): ennél frissebb kiadást még nem ajánlunk. */
    val releaseDelay: Duration = Duration.ZERO,
)

/** A repó azonossága a rögzítetthez képest. */
enum class RepoIdentity {
    /** Még nem volt rögzített azonosító: most kerül rögzítésre. */
    FIRST_SEEN,
    UNCHANGED,

    /** Azonos azonosító és tulajdonos, más név (tájékoztató). */
    RENAMED,

    /** Azonos azonosító, más tulajdonos (figyelmeztetés). */
    OWNER_CHANGED,
}

/** A repó azonosságának megítélése a telepítés előtt ([ReleaseDiscovery.confirmIdentity]). */
sealed interface RepoIdentityVerdict {
    /** A néven az elvárt azonosítójú repó van. [firstSeen]: nem volt elvárt azonosító (első találkozás, TOFU). */
    data class Confirmed(val repo: RepoMetadata, val firstSeen: Boolean) : RepoIdentityVerdict

    /** Azonos néven MÁS repó van: a telepítés tiltva (C. 19–20., kritikus). */
    data class Mismatch(val expected: ProviderRepoId, val actual: ProviderRepoId) : RepoIdentityVerdict

    /** Az azonosság most nem igazolható (hálózat, API-keret, eltűnt repó): a letöltés nem indulhat el. */
    data class Unconfirmed(val failure: UpdateFailure) : RepoIdentityVerdict
}

data class Discovery(
    /** A repó AKTUÁLIS adatai (átnevezés után az új név). */
    val repo: RepoMetadata,
    val identity: RepoIdentity,
    val resolved: ResolvedReleases,
    /** A szabályok (végleges / előzetes kiadás) szerint választott, APK-t tartalmazó kiadás; `null`, ha nincs. */
    val candidate: Release?,
)

/**
 * 1. lépés: frissítés keresése (docs/ARCHITECTURE.md 9. pont). Kizárólag kiadásokra épül, commitokra nem (C. 11.).
 *
 * Kérésszám: két API-hívás (repó + kiadások). A repó lekérése azért kell, mert csak így ismerhető fel a stabil
 * azonosító alapján az átnevezés, a tulajdonosváltás és a repó-eltérítés (azonos név, más repó; C. 19.).
 */
class ReleaseDiscovery(
    private val providers: ProviderRegistry,
    private val releaseLimit: Int = 10,
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun discover(source: TrackedSource): StepResult<Discovery> {
        val provider = providers.get(source.ref.provider) ?: return StepResult.Failed(UpdateFailure.Other("provider"))
        val repo = when (val byName = provider.repository(source.ref)) {
            is Fetch.Fresh -> byName.value
            Fetch.NotFound -> {
                // Átirányítás nélkül áthelyezett repó: a stabil azonosítóval még megtalálható.
                val id = source.repoId ?: return notFound()
                when (val byId = provider.repositoryById(id)) {
                    is Fetch.Fresh -> byId.value
                    Fetch.NotFound -> return notFound()
                    else -> return failure(byId)
                }
            }
            else -> return failure(byName)
        }

        if (source.repoId != null && repo.id != source.repoId) {
            // Azonos néven más repó: a régi törlődött, és valaki újat hozott létre a helyén (repojacking).
            return StepResult.Blocked(listOf(Finding(FindingKind.REPO_ID_CHANGED, Severity.CRITICAL, "${source.repoId.value} → ${repo.id.value}")))
        }
        val findings = mutableListOf<Finding>()
        val ownerChanged = if (source.ownerId != null && repo.ownerId != null) {
            repo.ownerId != source.ownerId
        } else {
            // Tulajdonos-azonosító nélkül a fiók átnevezése és az átadás nem különböztethető meg: óvatosan figyelmeztetünk.
            !repo.ref.owner.equals(source.ref.owner, ignoreCase = true)
        }
        val identity = when {
            ownerChanged -> {
                findings += Finding(FindingKind.REPO_OWNER_CHANGED, Severity.WARNING, "${source.ref.path} → ${repo.ref.path}")
                RepoIdentity.OWNER_CHANGED
            }
            repo.ref.key != source.ref.key -> {
                findings += Finding(FindingKind.REPO_RENAMED, Severity.INFO, "${source.ref.path} → ${repo.ref.path}")
                RepoIdentity.RENAMED
            }
            source.repoId == null -> RepoIdentity.FIRST_SEEN
            else -> RepoIdentity.UNCHANGED
        }

        val releases = when (val result = provider.releases(repo.ref, limit = releaseLimit)) {
            is Fetch.Fresh -> result.value
            Fetch.NotFound -> emptyList()
            else -> return failure(result, findings)
        }
        // Késleltetés: a túl friss kiadások kimaradnak, így a legutóbbi „elég régi” kiadás lesz a jelölt.
        val cutoff = clock.instant().minus(source.releaseDelay)
        val eligible = if (source.releaseDelay.isZero) releases else releases.filter { !it.date().isAfter(cutoff) }
        val resolved = ReleaseResolver.resolve(eligible)
        val candidate = if (source.includePrereleases) resolved.latestPrerelease ?: resolved.latestStable else resolved.latestStable
        if (candidate == null) {
            val detail = when {
                eligible.size < releases.size && releases.any { !it.isDraft } -> "delayed"
                resolved.hasAnyRelease -> "no-apk"
                else -> "no-release"
            }
            findings += Finding(FindingKind.NO_INSTALLABLE_RELEASE, Severity.INFO, detail)
        }
        return StepResult.Ok(Discovery(repo, identity, resolved, candidate), findings)
    }

    /**
     * A repó azonosságának megerősítése KÖZVETLENÜL a letöltés előtt (C. 19.), egyetlen API-hívással. A kézi telepítés
     * (adatlap) a kiadásokat név szerint kéri le, ezért a telepítés előtt mindig meg kell győződni arról, hogy a néven
     * most is az elvárt (a katalógusban aláírtan rögzített, illetve a követett sornál korábban rögzített) stabil
     * azonosítójú repó van: különben a törölt repó helyén azonos néven létrehozott idegen repóból (repojacking)
     * ELSŐ telepítésként települhetne alkalmazás, amit a platform nem véd.
     *
     * [expected] minden eleme kötelező: ha bármelyik eltér, az eredmény [RepoIdentityVerdict.Mismatch]. Ha a repó nem
     * kérhető le, az azonosság nem igazolt ([RepoIdentityVerdict.Unconfirmed]): ilyenkor a letöltés nem indulhat el.
     */
    suspend fun confirmIdentity(ref: RepoRef, expected: List<ProviderRepoId>): RepoIdentityVerdict {
        val provider = providers.get(ref.provider) ?: return RepoIdentityVerdict.Unconfirmed(UpdateFailure.Other("provider"))
        return when (val fetched = provider.repository(ref)) {
            is Fetch.Fresh -> {
                val actual = fetched.value.id
                val violated = expected.firstOrNull { it != actual }
                if (violated != null) RepoIdentityVerdict.Mismatch(violated, actual) else RepoIdentityVerdict.Confirmed(fetched.value, firstSeen = expected.isEmpty())
            }
            Fetch.NotFound -> RepoIdentityVerdict.Unconfirmed(UpdateFailure.RepoNotFound)
            else -> RepoIdentityVerdict.Unconfirmed(failure(fetched).failure)
        }
    }

    private fun notFound(): StepResult.Failed =
        StepResult.Failed(UpdateFailure.RepoNotFound, listOf(Finding(FindingKind.REPO_NOT_FOUND, Severity.WARNING)))

    private fun failure(fetch: Fetch<*>, findings: List<Finding> = emptyList()): StepResult.Failed = when (fetch) {
        is Fetch.RateLimited -> StepResult.Failed(UpdateFailure.RateLimited(fetch.until), findings)
        is Fetch.Unavailable -> StepResult.Failed(
            UpdateFailure.RepoUnavailable(fetch.reason),
            findings + Finding(FindingKind.REPO_UNAVAILABLE, Severity.WARNING, fetch.reason.name),
        )
        is Fetch.Failed -> StepResult.Failed(
            when (val error = fetch.error) {
                is SourceError.Network -> UpdateFailure.Network(error.description)
                is SourceError.Http -> UpdateFailure.Other("HTTP ${error.code}")
                SourceError.InvalidResponse -> UpdateFailure.Other("invalid response")
                is SourceError.Policy -> UpdateFailure.Other(error.violation.name)
            },
            findings,
        )
        // Feltételes kérést itt nem küldünk, és a NotFound-ot a hívó kezeli; ezek csak hibás szolgáltatónál fordulhatnak elő.
        Fetch.NotModified, Fetch.NotFound, is Fetch.Fresh -> StepResult.Failed(UpdateFailure.Other("unexpected"), findings)
    }
}
