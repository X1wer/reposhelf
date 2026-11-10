// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.model

import java.time.Instant

/** Egy repó szolgáltatófüggetlen metaadatai. Minden mező megbízhatatlan, külső forrásból jön (C. 17.). */
data class RepoMetadata(
    val ref: RepoRef,
    val id: ProviderRepoId,
    val ownerId: Long?,
    val description: String?,
    val licenseSpdx: String?,
    val defaultBranch: String?,
    val htmlUrl: String?,
    val isArchived: Boolean,
    val isDisabled: Boolean,
    val isFork: Boolean,
)

/** Egy kiadás (C. 11.: a frissítésfelismerés kizárólag kiadásokra épül). */
data class Release(
    val id: Long,
    val tagName: String,
    val name: String?,
    val isPrerelease: Boolean,
    val isDraft: Boolean,
    val createdAt: Instant?,
    val publishedAt: Instant?,
    val body: String?,
    val htmlUrl: String?,
    /** A GitHub immutable release jelzése; `null`, ha a szolgáltató nem közli. */
    val isImmutable: Boolean?,
    val assets: List<ReleaseAsset>,
)

/** Egy kiadás letölthető állománya. A [sha256] a szolgáltató által közölt érték (GitHub: `digest`), ha van. */
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val label: String?,
    val contentType: String?,
    val size: Long,
    val downloadUrl: String,
    val sha256: String?,
    val state: String?,
)

/** A README nyers Markdown-tartalma (a megjelenítés a 3. munkamenet feladata). */
data class Readme(
    val markdown: String,
    val path: String?,
)
