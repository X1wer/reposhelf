// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.catalog

import kotlinx.serialization.Serializable

/** A katalógusbejegyzés JSON-alakja (schemaVersion 1). Leírás: `catalog/docs/SCHEMA.md`. */
@Serializable
internal data class CatalogEntryDto(
    val id: String,
    val name: String,
    val source: CatalogSourceDto,
    val packageName: String? = null,
    val signingCertSha256: List<String> = emptyList(),
    val minVersionCode: Long? = null,
    val keywords: List<String> = emptyList(),
    val category: String,
    val assetFilter: String? = null,
)

@Serializable
internal data class CatalogSourceDto(
    val provider: String,
    val repository: String,
    val repositoryId: Long? = null,
)
