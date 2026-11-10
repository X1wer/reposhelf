// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.SourceProvider
import java.time.Instant

fun ref(path: String): RepoRef = checkNotNull(RepoRef.parsePath(ProviderId.GITHUB, path))

fun metadata(path: String, id: Long, ownerId: Long?) = RepoMetadata(
    ref = ref(path), id = ProviderRepoId(id), ownerId = ownerId, description = null, licenseSpdx = null,
    defaultBranch = "main", htmlUrl = "https://github.com/$path", isArchived = false, isDisabled = false, isFork = false,
)

fun asset(id: Long, name: String, size: Long = 1000, url: String = "https://github.com/o/r/releases/download/v/$name", sha256: String? = null) =
    ReleaseAsset(id, name, null, "application/vnd.android.package-archive", size, url, sha256, "uploaded")

fun release(
    id: Long,
    tag: String,
    assets: List<ReleaseAsset>,
    prerelease: Boolean = false,
    draft: Boolean = false,
    published: String = "2026-10-0${(id % 9) + 1}T10:00:00Z",
) = Release(id, tag, tag, prerelease, draft, Instant.parse(published), Instant.parse(published), null, null, null, assets)

/** Szkriptelhető szolgáltató: a válaszokat a teszt adja meg, a hívásokat számolja. */
class FakeProvider : SourceProvider {
    override val id: ProviderId = ProviderId.GITHUB
    var byName: (RepoRef) -> Fetch<RepoMetadata> = { Fetch.NotFound }
    var byId: (ProviderRepoId) -> Fetch<RepoMetadata> = { Fetch.NotFound }
    var releaseList: (RepoRef) -> Fetch<List<Release>> = { Fetch.Fresh(emptyList(), Validators(null, null)) }
    val calls = mutableListOf<String>()

    override fun parseRepoUrl(input: String): RepoRef? = null
    override suspend fun repository(ref: RepoRef, validators: Validators?) = byName(ref).also { calls += "repo:${ref.path}" }
    override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?) = byId(id).also { calls += "repoById:${id.value}" }
    override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> = Fetch.NotFound
    override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int) = releaseList(ref).also { calls += "releases:${ref.path}" }
    override fun downloadPolicy(maxBytes: Long): HttpPolicy = HttpPolicy.of("github.com", maxBodyBytes = maxBytes)
}

fun <T> fresh(value: T): Fetch<T> = Fetch.Fresh(value, Validators(null, null))
