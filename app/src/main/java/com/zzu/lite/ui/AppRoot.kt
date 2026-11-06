package com.zzu.lite.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.di.SessionState
import com.zzu.lite.ui.classroom.ClassroomScreen
import com.zzu.lite.ui.login.LoginScreen
import com.zzu.lite.ui.services.ServicesScreen
import com.zzu.lite.ui.today.TodayScreen
import com.zzu.lite.ui.timetable.TimetableScreen

private data class TabSpec(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec("today", "今日", Icons.Filled.Home),
    TabSpec("timetable", "课表", Icons.Filled.DateRange),
    TabSpec("classroom", "空教室", Icons.Filled.Place),
    TabSpec("services", "服务", Icons.Filled.Settings)
)

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val session by container.session.collectAsState()

    when (session) {
        is SessionState.LoggedOut -> LoginScreen()
        is SessionState.LoggedIn -> MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(padding)
        ) {
            composable("today") { TodayScreen() }
            composable("timetable") { TimetableScreen() }
            composable("classroom") { ClassroomScreen() }
            composable("services") { ServicesScreen() }
        }
    }
}
