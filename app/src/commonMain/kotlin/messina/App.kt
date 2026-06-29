package messina

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.remember
import messina.sensors.Sensors
import messina.share.Share
import messina.settings.Settings
import messina.settings.Theme
import messina.ui.main.MainScreen
import messina.ui.scan.NfcScreen
import messina.sensors.SensorId
import messina.ui.sensor.EventLogScreen
import messina.ui.sensor.EventDetailsScreen
import messina.ui.sensor.SensorDetailsScreen
import messina.ui.sensor.SensorScreen
import messina.ui.settings.AdvancedSettingsScreen
import messina.ui.settings.AlarmScreen
import messina.ui.settings.SettingsScreen
import messina.ui.share.LibreViewScreen
import messina.ui.share.NightScoutScreen
import messina.ui.share.ShareScreen

object GlobalState {
    fun initialize() {
        // Initialize objects
        Database
        Settings
        Sensors
        Share
    }
}

@Composable
fun App() {
    MaterialTheme(colorScheme = Theme.colorScheme()) {
        // Workaround for text fields not being unfocused when the keyboard is hidden
        val focusManager = LocalFocusManager.current
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        LaunchedEffect(imeVisible) {
            if (!imeVisible) focusManager.clearFocus()
        }

        val navController = rememberNavController()
        NavHost(
            modifier = if (Settings.keepScreenOn) Modifier.keepScreenOn() else Modifier,
            navController = navController,
            startDestination = "main",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
        ) {
            composable("main") {
                MainScreen(
                    onNavigateToSettings = { navController.navigate("settings") },
                    navigateToSensor = { navController.navigate("sensor") },
                    onNavigateToScan = { navController.navigate("scan") },
                    onNavigateToShare = { navController.navigate("share") },
                    onNavigateToLibreView = { navController.navigate("share/libreview") },
                )
            }
            composable("scan") {
                NfcScreen(onBack = { navController.popBackStack() })
            }
            composable("sensor") {
                SensorScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToSensorDetail = { sensorId ->
                        navController.navigate("sensor/${sensorId.value}")
                    },
                    onNavigateToEventLog = { sensorId ->
                        navController.navigate("sensor/${sensorId.value}/events")
                    },
                )
            }
            composable("sensor/{sensorId}") { backStackEntry ->
                val sensorId = backStackEntry.arguments?.getString("sensorId")?.toLong()
                    ?.let { SensorId(it) } ?: return@composable
                SensorDetailsScreen(
                    sensorId = sensorId,
                    onBack = { navController.popBackStack() },
                    onNavigateToEventLog = {
                        navController.navigate("sensor/${sensorId.value}/events")
                    },
                )
            }
            composable("sensor/{sensorId}/events") { backStackEntry ->
                val sensorId = backStackEntry.arguments?.getString("sensorId")?.toLong()
                    ?.let { SensorId(it) } ?: return@composable
                EventLogScreen(
                    sensorId = sensorId,
                    onBack = { navController.popBackStack() },
                    onNavigateToEvent = { index ->
                        navController.navigate("sensor/${sensorId.value}/events/$index")
                    },
                )
            }
            composable("sensor/{sensorId}/events/{index}") { backStackEntry ->
                val sensorId = backStackEntry.arguments?.getString("sensorId")?.toLong()
                    ?.let { SensorId(it) } ?: return@composable
                val index = backStackEntry.arguments?.getString("index")?.toIntOrNull()
                    ?: return@composable
                EventDetailsScreen(
                    sensorId = sensorId,
                    index = index,
                    onBack = { navController.popBackStack() })
            }
            composable("share") {
                ShareScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToLibreView = { navController.navigate("share/libreview") },
                    onNavigateToNightScout = { navController.navigate("share/nightscout") },
                )
            }
            composable("share/libreview") {
                LibreViewScreen(onBack = { navController.popBackStack() })
            }
            composable("share/nightscout") {
                NightScoutScreen(onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToAdvanced = { navController.navigate("settings/advanced") },
                    onNavigateToAlarm = { alarm ->
                        val index = Settings.alarms.indexOf(alarm)
                        // TODO: Have to manually check the route, if you tap two alarms in
                        //  sequence it will navigate to both. Probably because of the navigation
                        //  transition, surely this can't be the default behaviour
                        //  Maybe like this:
                        //  if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                        //      navigate(route)
                        //  }
                        if (index >= 0 && navController.currentBackStackEntry?.destination?.route == "settings") {
                            navController.navigate("alarm/$index")
                        }
                    }
                )
            }
            composable("settings/advanced") {
                AdvancedSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("alarm/{index}") { backStackEntry ->
                val index = backStackEntry.arguments?.getString("index")!!.toInt()
                val alarm = remember { Settings.alarms.getOrNull(index) } ?: return@composable
                AlarmScreen(
                    alarm = alarm,
                    onBack = { navController.popBackStack() })
            }
        }
    }
}