// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.codeberg

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.SourceProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * LETILTOTT, minimális Codeberg (Forgejo) szolgáltató (B. 7.6.): csak azt mutatja meg, hogy a [SourceProvider] interfész
 * egy második szolgáltatóra is alkalmas. Nincs regisztrálva az alkalmazásban, és hálózati kérést nem végez: minden
 * lekérés „nem megvalósított” hibát ad (HTTP 501). A valódi megvalósítás a Forgejo API-ra épülne
 * (`/api/v1/repos/{owner}/{repo}`, `…/releases`, `/api/v1/repositories/{id}`), lásd docs/ARCHITECTURE.md 5.2.
 */
class CodebergStubProvider : SourceProvider {
    override val id = ProviderId.CODEBERG

    override fun parseRepoUrl(input: String): RepoRef? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 300 || text.any { it.isWhitespace() || it.isISOControl() }) return null
        val url = (if (text.contains("://")) text else "https://$text").toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host.lowercase() != HOST || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.takeIf { it.size >= 2 } ?: return null
        return RepoRef.parsePath(ProviderId.CODEBERG, "${segments[0]}/${segments[1].removeSuffix(".git")}")
    }

    override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> = NOT_IMPLEMENTED
    override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> = NOT_IMPLEMENTED
    override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> = NOT_IMPLEMENTED
    override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> = NOT_IMPLEMENTED

    /** A kiadási állományok a Codeberg saját hosztjáról jönnének. */
    override fun downloadPolicy(maxBytes: Long): HttpPolicy = HttpPolicy.of(HOST, maxBodyBytes = maxBytes)

    private companion object {
        const val HOST = "codeberg.org"
        val NOT_IMPLEMENTED = Fetch.Failed(SourceError.Http(501))
    }
}
