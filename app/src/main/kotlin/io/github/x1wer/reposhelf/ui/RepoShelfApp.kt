// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
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
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.ui.common.OfflineBanner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.ui.apps.MyAppsScreen
import io.github.x1wer.reposhelf.ui.apps.UpdatesScreen
import io.github.x1wer.reposhelf.ui.details.DetailsKey
import io.github.x1wer.reposhelf.ui.details.DetailsScreen
import io.github.x1wer.reposhelf.ui.history.HistoryScreen
import io.github.x1wer.reposhelf.ui.settings.SelfUpdatePrompt
import io.github.x1wer.reposhelf.ui.settings.SettingsScreen
import io.github.x1wer.reposhelf.ui.sources.SourcesScreen
import io.github.x1wer.reposhelf.ui.discover.DiscoverScreen

/**
 * A főmenü (C. 22.1.): Felfedezés, Alkalmazásaim, Frissítések, Saját források, Beállítások. A navigációs sávban
 * rövidebb felirat ([barLabelRes]) is lehet, mert öt elem fér el egy telefon szélességén (a képernyőcím a teljes név).
 */
enum class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector, @StringRes val barLabelRes: Int = labelRes) {
    DISCOVER("discover", R.string.nav_discover, Icons.Filled.Search),
    MY_APPS("my_apps", R.string.nav_my_apps, Icons.AutoMirrored.Filled.List, R.string.nav_bar_my_apps),
    UPDATES("updates", R.string.nav_updates, Icons.Filled.Refresh),
    SOURCES("sources", R.string.nav_sources, Icons.Filled.Add, R.string.nav_bar_sources),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val DETAILS_ROUTE = "app"
private const val DETAILS_ARG = "id"
private const val TRACKED_ROUTE = "tracked"
private const val HISTORY_ROUTE = "history"
private val CATALOG_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

/** Ettől a szélességtől (tablet, fekvő telefon) oldalsó navigációs sáv és korlátozott tartalomszélesség (C. 22.2.). */
private const val WIDE_SCREEN_DP = 600
private val MAX_CONTENT_WIDTH = 840.dp

/**
 * A fülre koppintás mindig a fül kezdőképernyőjét mutatja (a fülről korábban megnyitott adatlap nem jön vissza): ha a
 * kezdőképernyő a veremben van, odáig lépünk vissza, különben a kezdő fül fölé nyitjuk meg.
 */
private fun NavHostController.openTopLevel(destination: TopLevelDestination) {
    if (popBackStack(destination.route, inclusive = false)) return
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

private fun NavDestination?.isIn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

@Composable
fun RepoShelfApp(
    openTrackedRequest: Long? = null,
    onOpenHandled: () -> Unit = {},
    openSelfUpdateRequest: Boolean = false,
    onOpenSelfUpdateHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // Értesítésből: a saját frissítés a Beállításokban (C. 14.).
    LaunchedEffect(openSelfUpdateRequest) {
        if (!openSelfUpdateRequest) return@LaunchedEffect
        navController.openTopLevel(TopLevelDestination.SETTINGS)
        onOpenSelfUpdateHandled()
    }
    // Értesítésből: közvetlenül az érintett alkalmazás adatlapja (C. 13.).
    LaunchedEffect(openTrackedRequest) {
        val id = openTrackedRequest ?: return@LaunchedEffect
        navController.navigate("$TRACKED_ROUTE/$id") { launchSingleTop = true }
        onOpenHandled()
    }
    // A saját frissítésről szóló semleges kérdés, egyszer (D-062).
    SelfUpdatePrompt()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= WIDE_SCREEN_DP.dp
    Scaffold(
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination.isIn(destination),
                            onClick = { navController.openTopLevel(destination) },
                            // A felirat mindig látszik, így a TalkBack is felolvassa; az ikon ezért dekoratív. Nagy
                            // betűmérettel a felirat egy sorban marad, levágva (a TalkBack a teljes szöveget olvassa).
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.barLabelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
      val context = LocalContext.current
      val online by remember { (context.applicationContext as RepoShelfApplication).container.connectivity.online }.collectAsState()
      Row(Modifier.padding(innerPadding)) {
        if (wide) {
            // A Scaffold már levonta a rendszersávokat: a sáv ne számolja újra.
            NavigationRail(windowInsets = WindowInsets(0)) {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = currentDestination.isIn(destination),
                        onClick = { navController.openTopLevel(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.barLabelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
      Column(Modifier.weight(1f)) {
        // Offline: a helyi funkciók működnek; a csak online műveletekről a sáv tájékoztat (C. 24.).
        if (!online) OfflineBanner()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.DISCOVER.route,
            // A külső Scaffold már levonta az állapotsor helyét: a képernyők felső sávja ne számolja újra.
            // Széles kijelzőn a tartalom nem nyúlik a teljes szélességre (olvashatóság).
            modifier = Modifier.fillMaxHeight().widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars),
            // Képernyőváltáskor nincs animáció (C. 22.2.: kerüld a fölösleges animációkat).
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            val openTracked: (TrackedApp) -> Unit = { app ->
                // Mindig a követett sor adatlapja: a katalógusbejegyzést az adatlap csak akkor kapcsolja hozzá, ha az
                // most is ugyanarra a repóra mutat (a katalógusazonosító önmagában nem azonosítja a repót).
                navController.navigate("$TRACKED_ROUTE/${app.id}")
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
                UpdatesScreen(onOpenApp = openTracked, onOpenHistory = { navController.navigate(HISTORY_ROUTE) })
            }
            composable(HISTORY_ROUTE) {
                HistoryScreen(onBack = { navController.popBackStack() })
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
      }
    }
}
