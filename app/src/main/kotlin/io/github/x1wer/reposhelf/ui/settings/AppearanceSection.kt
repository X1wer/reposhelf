// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.ui.common.AppLanguage
import io.github.x1wer.reposhelf.ui.common.AppLocales
import io.github.x1wer.reposhelf.ui.common.ThemeMode

/** Téma, dinamikus színek és nyelv (C. 22.2., D-132). A betűméret a rendszerét követi (a szövegek `sp` egységűek). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSection(container: AppContainer) {
    val context = LocalContext.current
    val theme by container.appearance.theme.collectAsStateWithLifecycle()
    val dynamicColor by container.appearance.dynamicColor.collectAsStateWithLifecycle()
    val language = remember { AppLocales.current(context) }

    Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = theme == mode,
                onClick = { container.appearance.setTheme(mode) },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            },
                        ),
                    )
                },
            )
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SwitchRow(stringResource(R.string.appearance_dynamic_color), dynamicColor) { container.appearance.setDynamicColor(it) }
    }

    Text(stringResource(R.string.appearance_language), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { choice ->
            FilterChip(
                selected = language == choice,
                onClick = { if (choice != language) (context as? Activity)?.let { AppLocales.apply(it, choice) } },
                label = {
                    Text(
                        stringResource(
                            when (choice) {
                                AppLanguage.SYSTEM -> R.string.language_system
                                AppLanguage.ENGLISH -> R.string.language_english
                                AppLanguage.HUNGARIAN -> R.string.language_hungarian
                            },
                        ),
                    )
                },
            )
        }
    }
    Text(stringResource(R.string.appearance_text_size_note), style = MaterialTheme.typography.bodySmall)
}
