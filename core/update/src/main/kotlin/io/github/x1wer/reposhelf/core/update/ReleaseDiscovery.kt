// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ReleaseResolver
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

/** Egy követett alkalmazás forrása, a korábban rögzített azonosítókkal (C. 19.). */
data class TrackedSource(
    val ref: RepoRef,
    /** A rögzített stabil repó-azonosító; `null`, ha még nincs (első ellenőrzés). */
    val repoId: ProviderRepoId?,
    /** A rögzített tulajdonos-azonosító; `null`, ha nem ismert. */
    val ownerId: Long?,
    val includePrereleases: Boolean,
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
class ReleaseDiscovery(private val providers: ProviderRegistry, private val releaseLimit: Int = 10) {

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
        val resolved = ReleaseResolver.resolve(releases)
        val candidate = if (source.includePrereleases) resolved.latestPrerelease ?: resolved.latestStable else resolved.latestStable
        if (candidate == null) {
            findings += Finding(FindingKind.NO_INSTALLABLE_RELEASE, Severity.INFO, if (resolved.hasAnyRelease) "no-apk" else "no-release")
        }
        return StepResult.Ok(Discovery(repo, identity, resolved, candidate), findings)
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
