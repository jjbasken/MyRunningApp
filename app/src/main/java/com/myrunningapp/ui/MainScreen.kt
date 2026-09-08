package com.myrunningapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.myrunningapp.ui.detail.RunDetailScreen
import com.myrunningapp.ui.history.HistoryScreen
import com.myrunningapp.ui.nav.Routes
import com.myrunningapp.ui.nav.TopLevelDestination
import com.myrunningapp.ui.profile.ProfileScreen
import com.myrunningapp.ui.track.TrackScreen

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = TopLevelDestination.entries.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { androidx.compose.material3.Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TRACK,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.TRACK) {
                TrackScreen(onRunClick = { navController.navigate(Routes.runDetail(it)) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onRunClick = { runId -> navController.navigate(Routes.runDetail(runId)) })
            }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(
                route = Routes.RUN_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_RUN_ID) { type = NavType.LongType }),
            ) {
                RunDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
