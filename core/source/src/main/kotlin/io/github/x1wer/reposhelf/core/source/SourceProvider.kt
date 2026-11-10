// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.FetchResponse
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import java.time.Instant

/**
 * Szolgáltatófüggetlen lekérési eredmény (docs/ARCHITECTURE.md 5. pont).
 * A hívó a [Fresh] mellé kapott validátorokat eltárolja, és a következő kérésnél visszaadja (ETag / 304).
 */
sealed interface Fetch<out T> {
    data class Fresh<T>(val value: T, val validators: Validators) : Fetch<T>

    /** A tárolt adat érvényes (HTTP 304). */
    data object NotModified : Fetch<Nothing>

    /** A repó vagy az erőforrás nem létezik, vagy nem nyilvános (a GitHub privát repóra is 404-et ad). */
    data object NotFound : Fetch<Nothing>

    data class Unavailable(val reason: UnavailableReason) : Fetch<Nothing>

    /** Rate limit miatt átmenetileg nem kérdezhető le; [until] a várható folytatás ideje (C. 16.1.). */
    data class RateLimited(val until: Instant?) : Fetch<Nothing>

    data class Failed(val error: SourceError) : Fetch<Nothing>
}

enum class UnavailableReason {
    /** HTTP 451 (pl. DMCA). */
    LEGAL_TAKEDOWN,

    /** HTTP 403 „Repository access blocked” (pl. szabályzatsértés miatt letiltva). */
    ACCESS_BLOCKED,

    /** HTTP 410. */
    GONE,
}

sealed interface SourceError {
    data class Network(val description: String) : SourceError
    data class Http(val code: Int) : SourceError
    data object InvalidResponse : SourceError
    data class Policy(val violation: FetchResponse.Violation) : SourceError
}

/**
 * A forráskód-tárhely egységes interfésze (C. 4.). A felület és a frissítési logika csak ezt látja, a GitHub API-t nem.
 * A telepítőállományok azonosítását, a verziókezelést és a frissítésellenőrzést szolgáltatófüggetlen kód végzi
 * a [Release]-modellen, ezért azok nem részei ennek az interfésznek.
 */
interface SourceProvider {
    val id: ProviderId

    /** Repó felismerése felhasználói bemenetből (URL vagy `owner/name`). Nem végez hálózati kérést. */
    fun parseRepoUrl(input: String): RepoRef?

    suspend fun repository(ref: RepoRef, validators: Validators? = null): Fetch<RepoMetadata>

    /** Lekérés stabil azonosító alapján: így ismerhető fel az átnevezés és az áthelyezés (C. 19.). */
    suspend fun repositoryById(id: ProviderRepoId, validators: Validators? = null): Fetch<RepoMetadata>

    suspend fun readme(ref: RepoRef, validators: Validators? = null): Fetch<Readme>

    /** A legutóbbi [limit] kiadás (draftokkal együtt, ha a szolgáltató visszaadja; a szűrés a hívó dolga). */
    suspend fun releases(ref: RepoRef, validators: Validators? = null, limit: Int = 10): Fetch<List<Release>>

    /** A kiadási állományok letöltésének hálózati szabálya: mely hosztokra vezethet a letöltés (az átirányításokkal együtt). */
    fun downloadPolicy(maxBytes: Long): HttpPolicy
}

class ProviderRegistry(providers: List<SourceProvider>) {
    private val byId: Map<ProviderId, SourceProvider> = providers.associateBy { it.id }

    init {
        require(byId.size == providers.size) { "Szolgáltatónként egy implementáció lehet" }
    }

    fun get(id: ProviderId): SourceProvider? = byId[id]

    /** Az első szolgáltató, amely felismeri a bemenetet. Ismeretlen hosztnál `null`. */
    fun parseRepoUrl(input: String): RepoRef? = byId.values.firstNotNullOfOrNull { it.parseRepoUrl(input) }
}
