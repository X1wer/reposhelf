// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.ui.common.PlaceholderScreen
import io.github.x1wer.reposhelf.ui.discover.DiscoverScreen

/** A főmenü (C. 22.1.): Felfedezés, Alkalmazásaim, Frissítések, Saját források, Beállítások. */
enum class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    DISCOVER("discover", R.string.nav_discover, Icons.Filled.Search),
    MY_APPS("my_apps", R.string.nav_my_apps, Icons.AutoMirrored.Filled.List),
    UPDATES("updates", R.string.nav_updates, Icons.Filled.Refresh),
    SOURCES("sources", R.string.nav_sources, Icons.Filled.Add),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun RepoShelfApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // A felirat mindig látszik, így a TalkBack is felolvassa; az ikon ezért dekoratív.
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.DISCOVER.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.DISCOVER.route) { DiscoverScreen() }
            composable(TopLevelDestination.MY_APPS.route) {
                PlaceholderScreen(R.string.nav_my_apps, R.string.placeholder_my_apps)
            }
            composable(TopLevelDestination.UPDATES.route) {
                PlaceholderScreen(R.string.nav_updates, R.string.placeholder_updates)
            }
            composable(TopLevelDestination.SOURCES.route) {
                PlaceholderScreen(R.string.nav_sources, R.string.placeholder_sources)
            }
            composable(TopLevelDestination.SETTINGS.route) {
                PlaceholderScreen(R.string.nav_settings, R.string.placeholder_settings)
            }
        }
    }
}
