// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.catalog.CatalogErrorKind
import io.github.x1wer.reposhelf.core.model.CatalogCategory

/** Helykitöltő képernyő a még el nem készült főmenüpontokhoz (B. rész 2.1.). */
@Composable
fun PlaceholderScreen(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.placeholder_in_development),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@StringRes
fun CatalogCategory.labelRes(): Int = when (this) {
    CatalogCategory.COMMUNICATION -> R.string.category_communication
    CatalogCategory.DEVELOPMENT -> R.string.category_development
    CatalogCategory.EDUCATION -> R.string.category_education
    CatalogCategory.GAMES -> R.string.category_games
    CatalogCategory.GRAPHICS -> R.string.category_graphics
    CatalogCategory.INTERNET -> R.string.category_internet
    CatalogCategory.MAPS -> R.string.category_maps
    CatalogCategory.MULTIMEDIA -> R.string.category_multimedia
    CatalogCategory.PRODUCTIVITY -> R.string.category_productivity
    CatalogCategory.READING -> R.string.category_reading
    CatalogCategory.SCIENCE -> R.string.category_science
    CatalogCategory.SECURITY -> R.string.category_security
    CatalogCategory.SYSTEM -> R.string.category_system
    CatalogCategory.UTILITIES -> R.string.category_utilities
    CatalogCategory.OTHER -> R.string.category_other
}

@Composable
fun CatalogErrorKind.describe(detail: String?): String = when (this) {
    CatalogErrorKind.NETWORK -> stringResource(R.string.error_network)
    CatalogErrorKind.HTTP -> stringResource(R.string.error_http, detail ?: "?")
    CatalogErrorKind.RATE_LIMITED -> stringResource(R.string.error_rate_limited)
    CatalogErrorKind.POLICY -> stringResource(R.string.error_policy)
    CatalogErrorKind.INVALID -> stringResource(R.string.error_invalid)
    CatalogErrorKind.REJECTED -> stringResource(R.string.error_rejected)
    CatalogErrorKind.UNSUPPORTED_SCHEMA -> stringResource(R.string.error_unsupported)
}
