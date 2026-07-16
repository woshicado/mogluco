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
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

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
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (text.isNotEmpty()) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    private fun readIntent(intent: android.content.Intent) {
        shown.value = WearAlarmService.RingingAlarm(
            intent.getStringExtra(WearAlarmService.EXTRA_TITLE) ?: "Glucose alarm",
            intent.getStringExtra(WearAlarmService.EXTRA_TEXT) ?: "",
        )
    }

    /**
     * Dismiss when the user leaves the alarm screen — this is how a crown press dismisses the alarm.
     *
     * On the Pixel Watch the crown short-press is claimed by the system as the Home action (like
     * HOME on a phone): it is handled at the framework level and never reaches [onKeyDown], so we
     * can't intercept the key. But leaving to Home fires [onUserLeaveHint], and crucially it does
     * NOT fire on a screen-off/sleep or when another activity covers us — so "user pressed the crown
     * to go home" cleanly maps to "dismiss", while an unattended alarm keeps ringing.
     *
     * [finish] from the on-screen Dismiss/Snooze buttons does not trigger onUserLeaveHint, so it
     * won't override a snooze.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        WearAlarmService.dismiss(this)
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
        /** Whether the surface is currently on screen — gates the service's launch retries. */
        @Volatile
        var visible: Boolean = false
            internal set
    }
}
