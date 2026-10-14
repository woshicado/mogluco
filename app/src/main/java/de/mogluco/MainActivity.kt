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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/** Largest font scale the bottom navigation's labels follow (one line in a quarter of the width). */
private const val NAV_LABEL_MAX_FONT_SCALE = 1.5f

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
                    activeSensors = {
                        runCatching { tk.glucodata.Sensors.activeSensors().toSet() }.getOrDefault(emptySet())
                    },
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
                val lossNotification by app.preferences.signalLossNotification.collectAsStateWithLifecycle()
                val keepAlive = persist || lowAlarm || highAlarm || lossAlarm || lossNotification
                androidx.compose.runtime.LaunchedEffect(keepAlive) {
                    // Notifications or not: the service runs fine without them (its notification
                    // just stays hidden), and alarms must not depend on them.
                    if (keepAlive) {
                        de.mogluco.notification.GlucoseNotificationService.start(context)
                    } else {
                        de.mogluco.notification.GlucoseNotificationService.stop(context)
                    }
                }

                // Notifications and background use are asked for once, on the first open, and only
                // where one of them is missing then: never again by itself once answered, whatever
                // the answer. (Settings → Notifications offers both for as long as they are
                // missing.) The note is made with the answer, not with the showing: a question that
                // never got one — the app closed over it — is still owed.
                val accessAsked by app.preferences.backgroundAccessAsked.collectAsStateWithLifecycle()
                var accessMissing by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                // Not while an alarm rings: the dialog would cover the banner that may be its only
                // Dismiss / Snooze (notifications blocked — a likely reason for the question).
                val alarmRinging = de.mogluco.notification.AlarmService.ringing
                    .collectAsStateWithLifecycle().value != null
                androidx.compose.runtime.LaunchedEffect(accessAsked) {
                    if (accessAsked) return@LaunchedEffect
                    accessMissing = !de.mogluco.ui.permissions.notificationsAllowed(context) ||
                        !de.mogluco.ui.permissions.backgroundUnrestricted(context)
                    // Nothing to ask for on the first open: nothing will be asked later either.
                    if (!accessMissing) app.preferences.setBackgroundAccessAsked(true)
                }
                if (!accessAsked && accessMissing && !alarmRinging) {
                    de.mogluco.ui.permissions.BackgroundAccessDialog(
                        onDone = { app.preferences.setBackgroundAccessAsked(true) },
                    )
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

                // A ringing alarm gets its Dismiss / Snooze on every screen of the app. With
                // notifications blocked (POST_NOTIFICATIONS denied) the alarm still rings, but its
                // notification and lock-screen alarm screen — the other stop controls — never show.
                val ringing by de.mogluco.notification.AlarmService.ringing.collectAsStateWithLifecycle()

                Scaffold(
                    topBar = { ringing?.let { RingingAlarmBanner(it) } },
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
                                    // One line at any font size: the item is a quarter of the
                                    // width, and at 2× "Calendar" broke into "Calend / ar".
                                    label = {
                                        de.mogluco.ui.util.FontScaleAtMost(NAV_LABEL_MAX_FONT_SCALE) {
                                            Text(tab.label, maxLines = 1, softWrap = false)
                                        }
                                    },
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
                                        onAddSensor = { openOnboarding(navController) },
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
                                onActivate = { openOnboarding(navController) },
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

    /** The in-app stop control for a ringing alarm, shown above every screen while it rings. */
    @androidx.compose.runtime.Composable
    private fun RingingAlarmBanner(alarm: de.mogluco.notification.AlarmService.RingingAlarm) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                // As the Scaffold's top bar this sits under the status bar, so take its inset here.
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        alarm.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (alarm.text.isNotEmpty()) {
                        Text(
                            alarm.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                // A test ring has nothing to snooze.
                if (!alarm.test) {
                    TextButton(onClick = { de.mogluco.notification.AlarmService.snooze(context) }) { Text("Snooze") }
                }
                Button(onClick = { de.mogluco.notification.AlarmService.dismiss(context) }) {
                    Text(if (alarm.test) "Stop" else "Dismiss")
                }
            }
        }
    }

    /**
     * Open the add-sensor flow from its first step. Its view model outlives the screen
     * (activity-scoped), so a finished earlier run would otherwise still sit on its "Sensor
     * activated" / "All set" step — only a Done button, NFC reader mode off — and no new sensor
     * could be scanned until the activity was destroyed.
     */
    private fun openOnboarding(navController: NavHostController) {
        onboardingViewModel.restart()
        navController.navigate("onboarding")
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

    companion object {
        /**
         * The intent for notification taps: resumes the running activity. A bare component intent
         * does not match the launcher task's root intent, so on a `standard` activity every tap
         * stacked another MainActivity on the task (Back then walked through the old copies).
         * Together with launchMode singleTop the existing one is brought back instead.
         */
        fun resumeIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
    }
}
