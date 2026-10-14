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
package de.mogluco.wear.alarm

import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.mogluco.wear.ui.WearTheme

/**
 * Full-screen alarm surface (launched by the alarm notification's full-screen intent):
 * wakes the screen, shows what's wrong, offers big round Dismiss / Snooze targets.
 */
class WearAlarmActivity : ComponentActivity() {

    // From the launching intent, refreshed if a different alarm replaces this one (singleTop
    // relaunch or a newer ringing state) — the surface must always show the CURRENT alarm.
    private val shown = mutableStateOf(WearAlarmService.RingingAlarm("Glucose alarm", ""))

    /** Uptime of the last ambient ENTER; a leave hint right after one is the system's. */
    @Volatile private var lastAmbientEnterUptime = 0L

    /** Drives the low-power ambient layout (dim text only, no colored fills — OLED-safe). */
    private val ambient = mutableStateOf(false)

    // Ambient support is what keeps an unattended alarm alive: without it, Wear OS brings the
    // watch face forward as soon as the display dims (screen-off timeout, wrist down, bedtime),
    // which fires onUserLeaveHint exactly like a crown press did — and that dismissed the alarm
    // after ~one vibration cycle. Ambient-aware, the surface simply stays on top while dimmed.
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                lastAmbientEnterUptime = SystemClock.uptimeMillis()
                ambient.value = true
            }

            override fun onUpdateAmbient() {}

            // Not stamped: a crown press while dimmed wakes us (exit) and may go Home right after —
            // that is a real dismiss and must not fall inside a grace window.
            override fun onExitAmbient() {
                ambient.value = false
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        lifecycle.addObserver(ambientObserver)

        readIntent(intent)
        // Track the ringing state: close when the alarm ends without user action here (dismissed
        // on the phone, value back in range, timeout) instead of lingering as a stale surface, and
        // adopt a newer alarm's texts if one replaces the current ring. finish() does not trigger
        // onUserLeaveHint, so closing never counts as a crown-dismiss.
        lifecycleScope.launch {
            WearAlarmService.ringing.collect { alarm ->
                if (alarm == null) finish() else shown.value = alarm
            }
        }

        setContent {
            WearTheme {
                val (title, text) = shown.value
                if (ambient.value) {
                    // Ambient: grey text on black, no icon/buttons — the alarm may sit on the
                    // always-on display for a long time (it rings until dismissed).
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = androidx.compose.ui.graphics.Color.LightGray,
                            textAlign = TextAlign.Center,
                        )
                        if (text.isNotEmpty()) {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = androidx.compose.ui.graphics.Color.Gray,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    return@WearTheme
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    // Centred lines: a detail that wraps ("This is how alarms feel on the watch.")
                    // must not run flush left into the edge of a round screen.
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (text.isNotEmpty()) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconButton(
                            onClick = {
                                WearAlarmService.snooze(this@WearAlarmActivity)
                                finish()
                            },
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(Icons.Rounded.Snooze, contentDescription = "Snooze")
                        }
                        IconButton(
                            onClick = {
                                WearAlarmService.dismiss(this@WearAlarmActivity)
                                finish()
                            },
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        started++
    }

    override fun onStop() {
        started--
        // Left the screen while still ringing (a leave that wasn't a crown press, the system
        // covering us, or a swipe/back that finished us — every real dismiss clears the ringing
        // state first): ask the service to bring the surface back so the alarm never rings on an
        // unrelated screen with nothing to dismiss it from. After a plain screen-off the service
        // waits for the screen to turn on again instead of waking it.
        if (WearAlarmService.ringing.value != null) {
            WearAlarmService.requestSurface(this)
        }
        super.onStop()
    }

    private fun readIntent(intent: android.content.Intent) {
        shown.value = WearAlarmService.RingingAlarm(
            intent.getStringExtra(WearAlarmService.EXTRA_TITLE) ?: "Glucose alarm",
            intent.getStringExtra(WearAlarmService.EXTRA_TEXT) ?: "",
        )
    }

    /**
     * Dismiss when the USER leaves the alarm screen — this is how a crown press dismisses the alarm.
     *
     * On the Pixel Watch the crown short-press is claimed by the system as the Home action (like
     * HOME on a phone): it is handled at the framework level and never reaches [onKeyDown], so we
     * can't intercept the key. Leaving to Home fires [onUserLeaveHint] — but so does the system
     * bringing the watch face forward when the display dims or sleeps (verified on a Pixel Watch 3:
     * the alarm screen was replaced by the watch face exactly at the 10 s screen-off timeout and the
     * alarm was dismissed after one vibration cycle). So a leave hint only counts as a crown press
     * when the screen is interactive, we're not ambient, and no ambient transition just happened.
     * Anything else leaves the alarm ringing; the service keeps vibrating until a real dismiss.
     *
     * [finish] from the on-screen Dismiss/Snooze buttons does not trigger onUserLeaveHint, so it
     * won't override a snooze.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isDeliberateLeave()) WearAlarmService.dismiss(this)
    }

    private fun isDeliberateLeave(): Boolean {
        if (ambientObserver.isAmbient) return false
        val interactive = runCatching {
            getSystemService(PowerManager::class.java).isInteractive
        }.getOrDefault(false)
        if (!interactive) return false
        val sinceAmbient = SystemClock.uptimeMillis() - lastAmbientEnterUptime
        return lastAmbientEnterUptime == 0L || sinceAmbient > AMBIENT_GRACE_MS
    }

    /**
     * Watches with real physical side buttons deliver them here (the Pixel Watch crown does not —
     * see [onUserLeaveHint]). Consuming the event dismisses without touching the display.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_STEM_PRIMARY,
        KeyEvent.KEYCODE_STEM_1,
        KeyEvent.KEYCODE_STEM_2,
        KeyEvent.KEYCODE_STEM_3 -> {
            WearAlarmService.dismiss(this)
            finish()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    companion object {
        /** A leave hint this soon after entering ambient is the system's, not the crown. */
        private const val AMBIENT_GRACE_MS = 1_500L

        // Started instances, not a flag: when a relaunch replaces a finishing instance, the new
        // one's onStart runs before the old one's onStop, which must not mark the surface gone.
        @Volatile
        private var started = 0

        /** Whether the surface is currently on screen — gates the service's launch retries. */
        val visible: Boolean get() = started > 0
    }
}
