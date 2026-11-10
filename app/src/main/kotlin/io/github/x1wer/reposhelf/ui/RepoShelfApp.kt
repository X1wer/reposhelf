// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.ui.apps.MyAppsScreen
import io.github.x1wer.reposhelf.ui.apps.UpdatesScreen
import io.github.x1wer.reposhelf.ui.details.DetailsKey
import io.github.x1wer.reposhelf.ui.details.DetailsScreen
import io.github.x1wer.reposhelf.ui.settings.SettingsScreen
import io.github.x1wer.reposhelf.ui.sources.SourcesScreen
import io.github.x1wer.reposhelf.ui.discover.DiscoverScreen

/** A főmenü (C. 22.1.): Felfedezés, Alkalmazásaim, Frissítések, Saját források, Beállítások. */
enum class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    DISCOVER("discover", R.string.nav_discover, Icons.Filled.Search),
    MY_APPS("my_apps", R.string.nav_my_apps, Icons.AutoMirrored.Filled.List),
    UPDATES("updates", R.string.nav_updates, Icons.Filled.Refresh),
    SOURCES("sources", R.string.nav_sources, Icons.Filled.Add),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val DETAILS_ROUTE = "app"
private const val DETAILS_ARG = "id"
private const val TRACKED_ROUTE = "tracked"
private val CATALOG_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

@Composable
fun RepoShelfApp(openTrackedRequest: Long? = null, onOpenHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    // Értesítésből: közvetlenül az érintett alkalmazás adatlapja (C. 13.).
    LaunchedEffect(openTrackedRequest) {
        val id = openTrackedRequest ?: return@LaunchedEffect
        navController.navigate("$TRACKED_ROUTE/$id") { launchSingleTop = true }
        onOpenHandled()
    }
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
            val openTracked: (TrackedApp) -> Unit = { app ->
                // Katalógusbeli appnál a katalógus-adatlap (a követés ott is látszik), saját hozzáadásúnál a követett sor.
                val route = app.catalogId?.let { "$DETAILS_ROUTE/${Uri.encode(it)}" } ?: "$TRACKED_ROUTE/${app.id}"
                navController.navigate(route)
            }
            composable(TopLevelDestination.DISCOVER.route) {
                DiscoverScreen(onOpenApp = { id -> navController.navigate("$DETAILS_ROUTE/${Uri.encode(id)}") })
            }
            composable(
                route = "$DETAILS_ROUTE/{$DETAILS_ARG}",
                arguments = listOf(navArgument(DETAILS_ARG) { type = NavType.StringType }),
            ) { entry ->
                // Az argumentum a saját belső útvonalunkból jön, de validáljuk (nincs exportált deep link).
                val id = entry.arguments?.getString(DETAILS_ARG).orEmpty()
                if (CATALOG_ID.matches(id)) DetailsScreen(key = DetailsKey.Catalog(id), onBack = { navController.popBackStack() })
            }
            composable(
                route = "$TRACKED_ROUTE/{$DETAILS_ARG}",
                arguments = listOf(navArgument(DETAILS_ARG) { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong(DETAILS_ARG) ?: 0L
                if (id > 0) DetailsScreen(key = DetailsKey.Tracked(id), onBack = { navController.popBackStack() })
            }
            composable(TopLevelDestination.MY_APPS.route) {
                MyAppsScreen(onOpenApp = openTracked)
            }
            composable(TopLevelDestination.UPDATES.route) {
                UpdatesScreen(onOpenApp = openTracked)
            }
            composable(TopLevelDestination.SOURCES.route) {
                SourcesScreen(onOpenTracked = { id -> navController.navigate("$TRACKED_ROUTE/$id") })
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}
