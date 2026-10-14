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
package de.mogluco.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import de.mogluco.wear.alarm.WearAlarmActivity
import de.mogluco.wear.alarm.WearAlarmService
import de.mogluco.wear.service.WearGlucoseService
import kotlinx.coroutines.launch
import de.mogluco.wear.ui.WearTheme
import de.mogluco.wear.ui.graph.WearGraphScreen
import de.mogluco.wear.ui.home.WearHomeScreen
import de.mogluco.wear.ui.settings.WearSettingsScreen

/**
 * Single-activity watch app: home (big value + trend) with graph and settings pushed on a
 * swipe-dismissable stack, matching the standard Wear navigation idiom.
 */
class MainActivity : ComponentActivity() {

    // The destination a launcher (e.g. a tapped complication) asked us to open, observed by the
    // nav host. Set on first launch and on onNewIntent (when we're already running).
    private val navRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = WearApp.from(this)
        // Ask once up front for everything the watch needs:
        //  - POST_NOTIFICATIONS (33+): the ongoing chip + alarms.
        //  - BLUETOOTH_SCAN/CONNECT (31+): REQUIRED for the direct sensor↔watch connection after a
        //    "Send sensor to watch" hand-off. Without these grants Applic.canBluetooth() is false and
        //    the watch silently can't scan/connect the Libre 3, so the transfer never completes.
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                .launch(needed.toTypedArray())
        }
        // The keep-alive service holds the process for data + alarms + chip. Started here
        // (foreground context, so the start is always allowed); BootReceiver (reboot, app update)
        // and WearApp (any other process start) bring it back without the app being opened.
        WearGlucoseService.start(this)
        // Continuous background sensor scanning wants a Doze exemption — ask once if not granted.
        // Only mark it "asked" if the request actually launched (on some watches the direct intent
        // isn't handled), otherwise a silent failure would suppress it forever.
        var promptedThisLaunch = false
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName) && !app.preferences.batteryOptAsked()) {
                if (requestBatteryExemption(this)) {
                    app.preferences.setBatteryOptAsked()
                    promptedThisLaunch = true
                }
            }
        }
        // Background alarms need full-screen-intent access to open the crown-dismissible full-screen
        // surface on API 34+; without it they silently degrade to a heads-up notification (no
        // full-screen, no crown-dismiss). Because a missed alarm is a safety issue, re-prompt on EVERY
        // launch while the grant is missing — a previous one-and-done ask left it off for good after a
        // single decline. Once granted, canUseFullScreenAlarm() is true and this no longer fires.
        // Skipped only if the battery prompt just launched, so two system screens don't stack on a
        // fresh install; the Home warning and the Watch settings card remain the durable paths.
        if (!promptedThisLaunch) {
            runCatching {
                if (!canUseFullScreenAlarm(this)) requestFullScreenAlarmAccess(this)
            }
        }
        navRequest.value = intent?.getStringExtra(EXTRA_DESTINATION)
        // If an alarm is ringing, opening the app — from ANY entry point: launcher, complication,
        // tile, ongoing chip, notification — must land on the full-screen alarm surface (where the
        // crown dismisses), not on the glucose screens underneath a vibrating watch. The service's
        // own direct launch is blocked by the platform when the app isn't visible and the
        // full-screen intent often degrades to a heads-up pop; this path can't be blocked, because
        // a RESUMED activity is exactly what background-activity-launch rules allow. Also covers an
        // alarm that fires while the user is already in the app, and a snooze expiring there.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                WearAlarmService.ringing.collect { alarm ->
                    if (alarm != null) {
                        startActivity(
                            Intent(this@MainActivity, WearAlarmActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                                .putExtra(WearAlarmService.EXTRA_TITLE, alarm.title)
                                .putExtra(WearAlarmService.EXTRA_TEXT, alarm.text),
                        )
                    }
                }
            }
        }
        setContent {
            WearTheme {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    // Honor a requested destination from a launcher tap. The complication and graph
                    // tile ask for the graph but keep Home under it, so a back-swipe returns to Home;
                    // the ongoing notification asks for Home and resets the stack to it (so it lands
                    // on Home even if we were already sitting on the graph or settings).
                    val request by navRequest
                    LaunchedEffect(request) {
                        when (request) {
                            DEST_GRAPH -> {
                                nav.navigate("graph") {
                                    popUpTo("home") { inclusive = false } // keep Home: back returns here
                                    launchSingleTop = true
                                }
                                navRequest.value = null // consume so it doesn't re-fire on recomposition
                            }
                            DEST_HOME -> {
                                nav.navigate("home") {
                                    popUpTo("home") { inclusive = true } // reset stack to Home
                                    launchSingleTop = true
                                }
                                navRequest.value = null
                            }
                        }
                    }
                    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            WearHomeScreen(
                                repository = app.glucoseRepository,
                                onOpenGraph = { nav.navigate("graph") },
                                onOpenSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("graph") {
                            WearGraphScreen(repository = app.glucoseRepository)
                        }
                        composable("settings") {
                            WearSettingsScreen(
                                preferences = app.preferences,
                                repository = app.glucoseRepository,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already running and brought forward by a complication tap: re-request the destination.
        navRequest.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    companion object {
        const val EXTRA_DESTINATION = "de.mogluco.wear.DESTINATION"
        const val DEST_GRAPH = "graph"
        const val DEST_HOME = "home"

        // PendingIntent request codes, one per destination: extras are not part of a
        // PendingIntent's identity, so with a shared code the last one created (FLAG_UPDATE_CURRENT)
        // decides where the ongoing chip AND the complications open.
        const val REQUEST_HOME = 0
        const val REQUEST_GRAPH = 1
    }
}
