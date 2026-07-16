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
package de.mogluco.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mogluco.MoglucoApp
import de.mogluco.onboarding.OnboardingStep
import de.mogluco.onboarding.OnboardingViewModel
import de.mogluco.ui.theme.MoglucoTheme
import kotlin.math.max

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state.step) {
                OnboardingStep.Permissions -> PermissionsStep(onGranted = viewModel::onPermissionsResolved)
                OnboardingStep.Scan -> ScanStep()
                OnboardingStep.Activating -> ActivatingStep()
                OnboardingStep.WarmingUp ->
                    WarmingUpStep(state.warmupEndMillis, onContinue = onDone)
                OnboardingStep.Done -> DoneStep(onContinue = onDone)
                OnboardingStep.Error -> ErrorStep(state.error, onRetry = viewModel::retry)
            }
        }
    }
}

@Composable
private fun PermissionsStep(onGranted: () -> Unit) {
    // Bluetooth (pre-S: location, which gated BLE scanning) is how readings arrive at all, so
    // those grants are required to proceed. POST_NOTIFICATIONS is asked alongside but stays
    // optional — alarms and readings work without it, the user just sees fewer notifications.
    val required = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val requested = remember {
        required + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }
    val activity = LocalContext.current as Activity
    var denied by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Don't advance on a denial: without Bluetooth the sensor never connects and the flow
        // would end in an unexplained, eternal warm-up screen.
        val missing = required.filter {
            result[it] != true &&
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            denied = true
            // Denied with no rationale to show = "don't ask again": the system dialog won't
            // appear any more, so the only remaining path is the app-settings page.
            permanentlyDenied = missing.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
        }
    }
    // Coming back from app settings there's no result callback — re-check on resume and move
    // on if the user granted Bluetooth there.
    LifecycleResumeEffect(Unit) {
        if (denied && required.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            onGranted()
        }
        onPauseOrDispose { }
    }

    StepScaffold(
        icon = {
            Icon(
                Icons.Filled.Nfc,
                null,
                tint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
        },
        title = if (denied) "Bluetooth permission needed" else "Add your Libre3 sensor",
        body = if (denied) {
            "Readings can only arrive over Bluetooth, so the sensor can't be used without it. " +
                if (permanentlyDenied) {
                    "Allow the Nearby devices permission for Mogluco in app settings."
                } else {
                    "Please allow Bluetooth to continue."
                }
        } else {
            "Mogluco needs Bluetooth to receive readings and notifications for alarms. " +
                "Next you'll hold your phone to the new sensor to activate it."
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { launcher.launch(requested.toTypedArray()) }) {
                Text(if (denied) "Try again" else "Grant permissions")
            }
            if (permanentlyDenied) {
                TextButton(onClick = {
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", activity.packageName, null),
                            ),
                        )
                    }
                }) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun ScanStep() {
    // Without these checks an NFC-less or NFC-off phone would sit on the pulsing "hold your
    // sensor" prompt forever — reader mode silently does nothing in both cases.
    val context = LocalContext.current
    val nfc = remember(context) { (context.applicationContext as MoglucoApp).nfcController }
    val hasNfc = remember { nfc.isAvailable(context) }
    var nfcEnabled by remember { mutableStateOf(nfc.isEnabled(context)) }
    // The user toggles NFC in system settings and comes back — re-check on every resume.
    LifecycleResumeEffect(Unit) {
        nfcEnabled = nfc.isEnabled(context)
        onPauseOrDispose { }
    }

    when {
        !hasNfc -> StepScaffold(
            icon = { Icon(Icons.Filled.Nfc, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp)) },
            title = "No NFC on this phone",
            body = "Activating a Libre3 sensor requires NFC, and this phone doesn't have it.",
        )
        !nfcEnabled -> StepScaffold(
            icon = { Icon(Icons.Filled.Nfc, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp)) },
            title = "NFC is off",
            body = "Sensor activation happens over NFC. Turn it on, then hold your phone to the sensor.",
        ) {
            Button(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }) { Text("Open NFC settings") }
        }
        else -> {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by transition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "scale",
            )
            StepScaffold(
                icon = {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(scale)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Nfc,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                },
                title = "Hold your sensor to the phone",
                body = "Touch the top of your phone to the new Libre3 sensor and hold still until it " +
                    "activates.",
            )
        }
    }
}

@Composable
private fun ActivatingStep() {
    StepScaffold(
        icon = { CircularProgressIndicator(modifier = Modifier.size(72.dp)) },
        title = "Activating sensor…",
        body = "Keep the phone near the sensor.",
    )
}

@Composable
private fun WarmingUpStep(warmupEndMillis: Long?, onContinue: () -> Unit) {
    val remaining by produceState(initialValue = remainingMinutes(warmupEndMillis), warmupEndMillis) {
        while (true) {
            value = remainingMinutes(warmupEndMillis)
            kotlinx.coroutines.delay(30_000)
        }
    }
    StepScaffold(
        icon = { Icon(Icons.Filled.CheckCircle, null, tint = MoglucoTheme.glucose.inRange, modifier = Modifier.size(72.dp)) },
        title = "Sensor activated",
        body = if (remaining > 0) {
            "Warming up — your first reading will appear in about $remaining min. You can close " +
                "this; you'll be notified when it's ready."
        } else {
            "Warm-up complete. Readings should appear shortly."
        },
    ) {
        Button(onClick = onContinue) { Text("Done") }
    }
}

@Composable
private fun DoneStep(onContinue: () -> Unit) {
    StepScaffold(
        icon = { Icon(Icons.Filled.CheckCircle, null, tint = MoglucoTheme.glucose.inRange, modifier = Modifier.size(72.dp)) },
        title = "All set",
        body = "Your sensor is connected.",
    ) {
        Button(onClick = onContinue) { Text("Done") }
    }
}

@Composable
private fun ErrorStep(error: String?, onRetry: () -> Unit) {
    StepScaffold(
        icon = { Icon(Icons.Filled.Nfc, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp)) },
        title = "Couldn't activate",
        body = error ?: "Something went wrong. Move the sensor closer and try again.",
    ) {
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun StepScaffold(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    icon()
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    if (action != null) {
        Box(modifier = Modifier.padding(top = 32.dp)) { action() }
    }
}

private fun remainingMinutes(end: Long?): Int =
    if (end == null) 0 else max(0, ((end - System.currentTimeMillis()) / 60_000L).toInt())
