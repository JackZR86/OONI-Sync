package com.androidbackup.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.androidbackup.app.ui.screens.*
import com.androidbackup.app.ui.viewmodel.BackupViewModel

import androidx.compose.ui.platform.LocalContext
import com.androidbackup.app.ui.viewmodel.BackupViewModelFactory

@Composable
fun MainAppNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: BackupViewModel = run {
        val context = LocalContext.current
        viewModel(
            factory = androidx.compose.runtime.remember(context) {
                BackupViewModelFactory.provideFactory(context)
            }
        )
    }
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Screen.items.forEach { screen ->
                    val selected = currentDestination?.route == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onNavigateToFolders = {
                        navController.navigate(Screen.Folders.route)
                    }
                )
            }
            composable(Screen.Folders.route) {
                FoldersScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
            composable(Screen.Rules.route) {
                RulesScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    uiState = uiState
                )
            }
            composable(Screen.Logs.route) {
                LogScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}
