package de.mogluco.wear.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.GlucoseUnit
import de.mogluco.ui.home.TrendArrow
import de.mogluco.ui.theme.colorFor
import de.mogluco.wear.canUseFullScreenAlarm
import de.mogluco.wear.requestFullScreenAlarmAccess
import de.mogluco.wear.ui.WearMoglucoTheme
import de.mogluco.wear.ui.rememberLifecycleTicker

/**
 * The at-a-glance screen: big state-colored value + tilted trend arrow, reading age and
 * sensor day, with graph/settings entry points along the bottom edge.
 */
@Composable
fun WearHomeScreen(
    repository: GlucoseRepository,
    onOpenGraph: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val reading by repository.latest.collectAsState(initial = null)
    val unit by repository.unit.collectAsState(initial = GlucoseUnit.MGDL)
    val targetRange by repository.targetRange.collectAsState(initial = 70.0..180.0)
    val sensor by repository.sensor.collectAsState(initial = null)
    val context = LocalContext.current

    // Re-render every 60 s so the age line and staleness keep up without new readings — the age
    // text only has minute resolution, so ticking faster buys nothing. Lifecycle-gated: stops
    // while the app sits paused behind the watchface.
    val now by rememberLifecycleTicker(60_000)

    // Full-screen-intent access is what lets a background alarm come up full-screen and be
    // crown-dismissed; without it alarms silently fall back to a heads-up notification. Surface a
    // tappable warning here (re-checked on each tick — the ticker also fires immediately on
    // resume, so returning from the grant screen clears it right away) because a
    // silently-degraded glucose alarm is a safety issue the user must not miss.
    val alarmsLimited = remember(now) { !canUseFullScreenAlarm(context) }
    // The warning is the last line of the centred readout, in the wide middle of a round screen
    // (the top edge, where it used to sit, is the narrowest part and belongs to the time). One
    // line of text is no touch target, so while it shows the whole readout opens the grant screen.
    val readout = if (alarmsLimited) {
        Modifier.clickable(onClickLabel = "Allow full-screen alarms", role = Role.Button) {
            requestFullScreenAlarmAccess(context)
        }
    } else {
        Modifier
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            val r = reading
            if (r == null) {
                Column(
                    // Side padding: the second line is wider than a round screen and has to wrap
                    // inside the circle, centred, rather than run from edge to edge.
                    modifier = Modifier.align(Alignment.Center).then(readout).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Waiting for data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Values arrive from your phone or sensor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (alarmsLimited) AlarmAccessWarning()
                }
            } else {
                val glucose = WearMoglucoTheme.glucose
                val stale = r.isStale(now)
                val color = if (stale) glucose.stale else glucose.colorFor(
                    r.state(targetRange.start, targetRange.endInclusive),
                )
                Column(
                    modifier = Modifier.align(Alignment.Center).then(readout),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            unit.format(r.calibratedMgdl),
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                        TrendArrow(reading = r, tint = color, size = 34.dp)
                    }
                    Text(
                        ageText(r.ageMillis(now)) + if (stale) " · stale" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The warning takes the sensor-day line's place rather than adding a fourth, so
                    // the readout keeps the height it has between the time and the buttons; the
                    // sensor day is still in Settings.
                    if (alarmsLimited) {
                        AlarmAccessWarning()
                    } else {
                        sensor?.let { s ->
                            Text(
                                "Day ${s.dayOfLife(now)} of ${s.lifetimeDays}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onOpenGraph, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.ShowChart, contentDescription = "Graph")
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }
    }
}

/** Names the fix, as the tap on the readout around it performs it (see [WearHomeScreen]). */
@Composable
private fun AlarmAccessWarning() {
    Text(
        "⚠ Allow full-screen alarms",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
}

internal fun ageText(ageMillis: Long): String {
    val mins = ageMillis / 60_000
    return when {
        mins < 1 -> "just now"
        mins == 1L -> "1 min ago"
        mins < 60 -> "$mins min ago"
        else -> "${mins / 60} h ${mins % 60} min ago"
    }
}
