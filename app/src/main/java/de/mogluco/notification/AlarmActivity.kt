package de.mogluco.notification

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.mogluco.ui.theme.MoglucoTheme

/**
 * Full-screen alarm surface launched by [AlarmService]'s full-screen intent when the device is
 * locked or the screen is off. On an active, unlocked phone the system shows the alarm as a
 * heads-up banner instead and never launches this. It turns the screen on, shows the reason,
 * and offers Dismiss / Snooze (which route back to the ringing [AlarmService]).
 */
class AlarmActivity : ComponentActivity() {

    // Compose state, not locals read once in onCreate: the activity is singleTask, so a second
    // alarm while this is showing arrives via onNewIntent — the content must re-derive or the
    // screen keeps ringing with the previous alarm's title/text.
    private var title by mutableStateOf("Glucose alarm")
    private var text by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        readAlarmExtras(intent)

        setContent {
            MoglucoTheme {
                AlarmScreen(
                    title = title,
                    text = text,
                    onDismiss = { AlarmService.dismiss(this); finish() },
                    onSnooze = {
                        startService(
                            Intent(this, AlarmService::class.java)
                                .setAction(AlarmService.ACTION_SNOOZE),
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAlarmExtras(intent)
    }

    private fun readAlarmExtras(intent: Intent) {
        title = intent.getStringExtra(AlarmService.EXTRA_TITLE) ?: "Glucose alarm"
        text = intent.getStringExtra(AlarmService.EXTRA_TEXT).orEmpty()
    }
}

@Composable
private fun AlarmScreen(title: String, text: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text("Dismiss") }
            OutlinedButton(onClick = onSnooze) { Text("Snooze") }
        }
    }
}
