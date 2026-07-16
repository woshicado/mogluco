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
package de.mogluco.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A titled settings subscreen with a back button and a scrolling body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // The outer Scaffold already applies the status-bar inset; don't add it twice
                // (that pushed these subpage titles too far down).
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}

/** A labelled group of rows in a card. */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { Column { content() } }
    }
}

/** A tappable row that navigates somewhere, with an optional current-value summary. */
@Composable
fun NavRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailingIcon: ImageVector? = Icons.Filled.ChevronRight,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
        }
        // A spinner while an action is running takes precedence over the trailing icon. Otherwise:
        // defaults to a chevron; action rows pass null (nothing navigates), and rows that leave the
        // app (browser, e-mail) pass an open-in-new icon.
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (trailingIcon != null) {
            Icon(trailingIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row with a trailing switch. */
@Composable
fun SwitchRow(icon: ImageVector?, title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A dialog with a single numeric field, opened by tapping a value to type it directly. */
@Composable
fun NumberInputDialog(
    title: String,
    initial: Int,
    suffix: String = "",
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() } },
                singleLine = true,
                suffix = { if (suffix.isNotEmpty()) Text(suffix) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm); onDismiss() },
                enabled = text.toIntOrNull() != null,
            ) { Text("OK") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Keep only digits and a single decimal separator, mapping ',' → '.'. Comma-decimal keyboards
 * (de/nl/fr/es) emit ','; a plain digit filter silently turned "5,6" into "56" — a 10× error.
 */
internal fun filterDecimalInput(raw: String): String {
    val sb = StringBuilder()
    var seenSeparator = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            (c == '.' || c == ',') && !seenSeparator -> { sb.append('.'); seenSeparator = true }
        }
    }
    return sb.toString()
}

/**
 * A dialog to type a low/high pair directly, in the CURRENT display unit (it used to demand
 * mg/dL integers even in mmol/L mode). Typed values are clamped to the slider's 40–300 mg/dL
 * domain on confirm so they can't drive the chart band/alarms off-scale; [onConfirm] receives
 * mg/dL, the engine's unit.
 */
@Composable
fun TargetRangeDialog(
    lowMgdl: Int,
    highMgdl: Int,
    unit: de.mogluco.data.GlucoseUnit,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seed with the current values in the display unit. GlucoseUnit.format may emit a locale
    // decimal comma; filterDecimalInput normalizes it straight back to '.'.
    var low by remember { mutableStateOf(filterDecimalInput(unit.format(lowMgdl.toDouble()))) }
    var high by remember { mutableStateOf(filterDecimalInput(unit.format(highMgdl.toDouble()))) }
    // 18.0182 mirrors GlucoseUnit.fromMgdl's mmol divisor; 40..300 is the RangeSlider's domain.
    fun toClampedMgdl(text: String): Int? = text.toFloatOrNull()?.let { v ->
        val mgdl = if (unit == de.mogluco.data.GlucoseUnit.MMOLL) v * 18.0182f else v
        mgdl.roundToInt().coerceIn(40, 300)
    }
    val lo = toClampedMgdl(low)
    val hi = toClampedMgdl(high)
    val valid = lo != null && hi != null && lo < hi
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target range (${unit.label})") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = low, onValueChange = { v -> low = filterDecimalInput(v) },
                    label = { Text("Low") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = high, onValueChange = { v -> high = filterDecimalInput(v) },
                    label = { Text("High") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (lo != null && hi != null) onConfirm(lo, hi); onDismiss() },
                enabled = valid,
            ) { Text("OK") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A labelled single-choice segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabledOption: (T) -> Boolean = { true },
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = opt == selected,
                    enabled = enabledOption(opt),
                    onClick = { onSelect(opt) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(optionLabel(opt)) }
            }
        }
    }
}
