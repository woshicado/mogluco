/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*                                                                                   */
/*      Mogluco is free software: you can redistribute it and/or modify              */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Mogluco is distributed in the hope that it will be useful, but               */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Mogluco. If not, see <https://www.gnu.org/licenses/>.             */
/*                                                                                   */
package de.mogluco

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import de.mogluco.data.ThemeMode
import de.mogluco.onboarding.OnboardingStep
import de.mogluco.onboarding.OnboardingViewModel
import de.mogluco.ui.calendar.CalendarScreen
import de.mogluco.ui.calendar.DayDetailScreen
import de.mogluco.ui.home.HomeScreen
import de.mogluco.ui.home.HomeViewModel
import de.mogluco.ui.onboarding.OnboardingScreen
import de.mogluco.ui.settings.NotificationSettingsScreen
import de.mogluco.ui.settings.SensorSettingsScreen
import de.mogluco.ui.settings.SettingsScreen
import de.mogluco.ui.settings.SettingsViewModel
import de.mogluco.ui.settings.TargetAlarmsScreen
import de.mogluco.ui.stats.AverageScreen
import de.mogluco.ui.theme.MoglucoTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.AutoMirrored.Filled.ShowChart),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth),
    AVERAGES("Averages", Icons.Filled.BarChart),
    SETTINGS("Settings", Icons.Filled.Settings),
}

private const val ROUTE_MAIN = "main"

class MainActivity : ComponentActivity() {

    private val app get() = application as MoglucoApp

    private val homeViewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer { HomeViewModel(app.glucoseRepository, app.eventRepository, app.preferences) }
        }
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer { SettingsViewModel(app.preferences, app.glucoseRepository, app.eventRepository) }
        }
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        viewModelFactory {
            initializer {
                OnboardingViewModel(
                    app.sensorActivator,
                    app.glucoseRepository.latest,
                    sensorActive = { tk.glucodata.Sensors.activeSensors().isNotEmpty() },
                    onActivated = {
                        // The phone just activated this sensor over NFC, so the phone owns its
                        // BLE — reclaim the role from a watch that owned the previous sensor
                        // (otherwise the Sensors.start() ownership gate would stop the phone
                        // from scanning for its own sensor after the next restart).
                        tk.glucodata.WatchLink.onPhoneActivatedSensor()
                        app.glucoseRepository.showActivatedSensor()
                    },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by app.preferences.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MoglucoTheme(darkTheme = dark) {
                // Status bar icons must contrast the app background: light theme → dark icons,
                // dark theme → light icons. Without this the icons stayed white and vanished
                // against the light-mode surface. (Edge-to-edge is otherwise left as-is.)
                androidx.compose.runtime.SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
                // Lock to portrait when the user disabled landscape, regardless of rotation.
                val allowLandscape by app.preferences.allowLandscape.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(allowLandscape) {
                    requestedOrientation = if (allowLandscape) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                // Start/stop the keep-alive service. It runs when the ongoing notification is
                // wanted OR any glucose alarm is enabled — the service is the only thing keeping
                // the process (BLE + AlarmController) alive in the background, so the
                // notification pref alone must not decide whether alarms can fire.
                val context = androidx.compose.ui.platform.LocalContext.current
                val persist by app.preferences.persistentNotification.collectAsStateWithLifecycle()
                val lowAlarm by app.preferences.lowAlarmEnabled.collectAsStateWithLifecycle()
                val highAlarm by app.preferences.highAlarmEnabled.collectAsStateWithLifecycle()
                val lossAlarm by app.preferences.signalLossAlarm.collectAsStateWithLifecycle()
                val keepAlive = persist || lowAlarm || highAlarm || lossAlarm
                val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                ) {
                    // Start regardless of the answer: the FGS runs fine without POST_NOTIFICATIONS
                    // (the notification just stays hidden), and alarms must not depend on it.
                    de.mogluco.notification.GlucoseNotificationService.start(context)
                }
                androidx.compose.runtime.LaunchedEffect(keepAlive) {
                    if (keepAlive) {
                        val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.POST_NOTIFICATIONS,
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        // Ask at most once per process: this effect re-runs on every activity
                        // recreation, and re-launching the system dialog each time (or after a
                        // permanent denial, when it auto-denies without showing) is just spam.
                        if (needsPerm && !askedNotifPermission) {
                            askedNotifPermission = true
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            de.mogluco.notification.GlucoseNotificationService.start(context)
                        }
                    } else {
                        de.mogluco.notification.GlucoseNotificationService.stop(context)
                    }
                }

                val navController = rememberNavController()
                val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
                val scope = rememberCoroutineScope()
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route

                // The bottom bar is always visible. Which tab is "active" depends on the
                // pager when on the main pages, and on the subpage's parent section otherwise.
                val activeTab = when {
                    currentRoute == ROUTE_MAIN -> Tab.entries[pagerState.currentPage]
                    currentRoute == "sensorInfo" || currentRoute == "targetAlarms" ||
                        currentRoute == "notifications" || currentRoute == "watch" -> Tab.SETTINGS
                    currentRoute?.startsWith("dayDetail") == true -> Tab.CALENDAR
                    else -> Tab.entries[pagerState.currentPage]
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = tab == activeTab,
                                    onClick = {
                                        if (currentRoute != ROUTE_MAIN) {
                                            navController.popBackStack(ROUTE_MAIN, inclusive = false)
                                        }
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_MAIN,
                        modifier = Modifier.padding(padding),
                        enterTransition = { slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(160)) },
                        exitTransition = { fadeOut(tween(120)) },
                        popEnterTransition = { slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(160)) },
                        popExitTransition = { slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(140)) },
                    ) {
                        composable(ROUTE_MAIN) {
                            // System back on Calendar/Settings returns to Home instead of exiting
                            // the app — standard bottom-nav behaviour.
                            androidx.activity.compose.BackHandler(enabled = pagerState.currentPage != 0) {
                                scope.launch { pagerState.animateScrollToPage(0) }
                            }
                            // Swipeable Home / Calendar / Settings. Pre-compose the neighbouring
                            // page so a swipe doesn't build the (heavy) chart/calendar on the
                            // animation frame — the main cause of swipe stutter.
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondViewportPageCount = 1,
                            ) { page ->
                                when (Tab.entries[page]) {
                                    Tab.HOME -> HomeScreen(
                                        viewModel = homeViewModel,
                                        onAddSensor = { navController.navigate("onboarding") },
                                        onOpenSensorInfo = { navController.navigate("sensorInfo") },
                                    )
                                    Tab.CALENDAR -> CalendarScreen(
                                        viewModel = homeViewModel,
                                        onOpenDay = { day -> navController.navigate("dayDetail/$day") },
                                    )
                                    Tab.AVERAGES -> AverageScreen(viewModel = homeViewModel)
                                    Tab.SETTINGS -> SettingsScreen(
                                        viewModel = settingsViewModel,
                                        onOpenSensor = { navController.navigate("sensorInfo") },
                                        onOpenTargetAlarms = { navController.navigate("targetAlarms") },
                                        onOpenNotifications = { navController.navigate("notifications") },
                                        onOpenWatch = { navController.navigate("watch") },
                                        onOpenEventLogging = { navController.navigate("eventLogging") },
                                    )
                                }
                            }
                        }
                        composable("sensorInfo") {
                            SensorSettingsScreen(
                                viewModel = settingsViewModel,
                                onActivate = { navController.navigate("onboarding") },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("targetAlarms") {
                            TargetAlarmsScreen(viewModel = settingsViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("notifications") {
                            NotificationSettingsScreen(viewModel = settingsViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("watch") {
                            de.mogluco.ui.settings.WatchScreen(
                                prefs = (application as MoglucoApp).preferences,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("eventLogging") {
                            de.mogluco.ui.settings.EventLoggingScreen(
                                prefs = (application as MoglucoApp).preferences,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "dayDetail/{day}",
                            arguments = listOf(navArgument("day") { type = NavType.LongType }),
                        ) { entry ->
                            val day = entry.arguments?.getLong("day") ?: System.currentTimeMillis()
                            DayDetailScreen(
                                viewModel = homeViewModel,
                                dayStartMillis = day,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("onboarding") {
                            OnboardingRoute(navController)
                        }
                    }
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    app.nfcController.tags.collect { tag ->
                        onboardingViewModel.onTagDetected(tag)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun OnboardingRoute(navController: NavHostController) {
        val state by onboardingViewModel.state.collectAsStateWithLifecycle()
        // Reader mode must span Scan *and* Activating: the step flips to Activating the
        // moment a tag is detected, while Libre3SensorActivator is still mid-transceive on
        // that same tag — disabling reader mode then can kill a real activation. Key the
        // effect on the derived boolean (not the raw step) so the Scan→Activating transition
        // doesn't dispose+re-enable, which would drop the RF field mid-handshake.
        val readerOn = state.step == OnboardingStep.Scan || state.step == OnboardingStep.Activating
        DisposableEffect(readerOn) {
            if (readerOn) {
                app.nfcController.enable(this@MainActivity)
            } else {
                app.nfcController.disable(this@MainActivity)
            }
            onDispose { app.nfcController.disable(this@MainActivity) }
        }
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onDone = { navController.popBackStack() },
        )
    }

    private companion object {
        /** Process-wide "asked already" flag for POST_NOTIFICATIONS — see the keep-alive effect. */
        private var askedNotifPermission = false
    }
}
