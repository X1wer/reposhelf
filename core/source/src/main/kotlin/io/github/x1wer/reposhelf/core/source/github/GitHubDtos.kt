// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A GitHub REST API válaszainak csak a felhasznált mezői. Az ismeretlen mezőket a dekódoló figyelmen kívül hagyja.

@Serializable
internal data class GitHubOwnerDto(
    val login: String,
    val id: Long? = null,
)

@Serializable
internal data class GitHubLicenseDto(
    @SerialName("spdx_id") val spdxId: String? = null,
)

@Serializable
internal data class GitHubRepoDto(
    val id: Long,
    val name: String,
    val owner: GitHubOwnerDto,
    val description: String? = null,
    val license: GitHubLicenseDto? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val archived: Boolean = false,
    val disabled: Boolean = false,
    val fork: Boolean = false,
)

@Serializable
internal data class GitHubAssetDto(
    val id: Long,
    val name: String,
    val label: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val digest: String? = null,
    val state: String? = null,
)

@Serializable
internal data class GitHubReleaseDto(
    val id: Long,
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val immutable: Boolean? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)
