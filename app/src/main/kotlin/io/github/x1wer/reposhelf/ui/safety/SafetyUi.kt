// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.safety

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.safety.SecurityWarning
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.update.WarningCategory
import io.github.x1wer.reposhelf.ui.common.formatDateTime

@StringRes
fun WarningCategory.labelRes(): Int = when (this) {
    WarningCategory.REPO_GONE -> R.string.warn_cat_repo_gone
    WarningCategory.REPO_ID_CHANGED -> R.string.warn_cat_repo_id_changed
    WarningCategory.REPO_OWNER_CHANGED -> R.string.warn_cat_owner_changed
    WarningCategory.SIGNER_CHANGED -> R.string.warn_cat_signer_changed
    WarningCategory.PACKAGE_CHANGED -> R.string.warn_cat_package_changed
    WarningCategory.DOWNGRADE -> R.string.warn_cat_downgrade
    WarningCategory.CORRUPTED -> R.string.warn_cat_corrupted
    WarningCategory.INCOMPATIBLE -> R.string.warn_cat_incompatible
    WarningCategory.UNEXPECTED_FORMAT -> R.string.warn_cat_unexpected_format
    WarningCategory.UNVERIFIABLE_SOURCE -> R.string.warn_cat_unverifiable
}

@StringRes
fun Severity.labelRes(): Int = when (this) {
    Severity.CRITICAL -> R.string.warn_severity_critical
    Severity.WARNING -> R.string.warn_severity_warning
    Severity.INFO -> R.string.warn_severity_info
}

/**
 * Egy biztonsági figyelmeztetés (C. 20.): súlyosság, eset, rövid részlet és a következmény. A súlyosság szövegként is
 * megjelenik (nem csak színnel), és a következmény pontos: a kritikus eset nyugtázás után is tiltja az automatikus
 * telepítést, a figyelmeztetés csak a nyugtázásig.
 */
@Composable
fun WarningCard(
    warning: SecurityWarning,
    showAppName: Boolean,
    onAcknowledge: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val critical = warning.severity == Severity.CRITICAL
    val colors = if (critical) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val severity = stringResource(warning.severity.labelRes())
            Text(
                if (showAppName) "$severity · ${warning.appName}" else severity,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(warning.category.labelRes()), style = MaterialTheme.typography.bodyLarge)
            warning.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            when (warning.severity) {
                Severity.CRITICAL -> Text(stringResource(R.string.warn_effect_critical), style = MaterialTheme.typography.bodySmall)
                Severity.WARNING -> if (!warning.acknowledged) Text(stringResource(R.string.warn_effect_warning), style = MaterialTheme.typography.bodySmall)
                Severity.INFO -> Unit
            }
            Text(stringResource(R.string.warn_seen, formatDateTime(warning.lastSeenAt)), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (warning.acknowledged) {
                    Text(stringResource(R.string.warn_acknowledged), style = MaterialTheme.typography.labelMedium)
                } else if (onAcknowledge != null) {
                    TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.warn_acknowledge)) }
                }
                if (onOpen != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.warn_open)) }
            }
        }
    }
}
