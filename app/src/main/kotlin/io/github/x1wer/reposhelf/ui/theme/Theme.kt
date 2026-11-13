// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F8B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE6F5),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF52606D),
    tertiary = Color(0xFF6B5778),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93CCF4),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B70),
    onPrimaryContainer = Color(0xFFCFE6F5),
    secondary = Color(0xFFB9C8D7),
    tertiary = Color(0xFFD7BEE4),
    error = Color(0xFFFFB4AB),
)

/**
 * Világos, sötét és rendszertéma (C. 22.2.). Android 12+ alatt kérésre a rendszer dinamikus színeit használja.
 * A választás a Beállításokban (D-132): `AppearanceSettings`.
 */
@Composable
fun RepoShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
