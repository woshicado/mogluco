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
package de.mogluco.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import de.mogluco.data.EventCategory
import de.mogluco.data.EventType
import de.mogluco.data.LoggedEvent
import de.mogluco.ui.util.addDays
import de.mogluco.ui.util.localDayToUtc
import de.mogluco.ui.util.startOfDay
import de.mogluco.ui.util.utcToLocalDay
import java.util.Calendar

/** Material icon representing an event category (used by the legend). */
fun categoryIcon(category: EventCategory): ImageVector = when (category) {
    EventCategory.CARBS -> Icons.Filled.Restaurant
    EventCategory.INSULIN -> Icons.Filled.Vaccines
    EventCategory.ACTIVITY -> Icons.AutoMirrored.Filled.DirectionsRun
    EventCategory.BLOOD -> Icons.Filled.Bloodtype
    EventCategory.OTHER -> Icons.Filled.EditNote
}

/**
 * Icon for a specific event, which can vary within a category — fast vs slow insulin get
 * distinct glyphs (a bolt for rapid-acting, an hourglass for long-acting/basal).
 */
fun eventIcon(category: EventCategory, label: String): ImageVector {
    if (category == EventCategory.INSULIN) {
        val l = label.lowercase()
        return when {
            "slow" in l || "long" in l || "basal" in l -> Icons.Filled.HourglassBottom
            "fast" in l || "rapid" in l || "bolus" in l -> Icons.Filled.Bolt
            else -> Icons.Filled.Vaccines
        }
    }
    return categoryIcon(category)
}

/**
 * Expandable speed-dial FAB. Tapping the main button reveals one mini-FAB per event type;
 * picking one opens the entry sheet pre-selected to that type. Mirrors the styling of the
 * old single "Carbs / insulin" button, expanded to the full taxonomy.
 */
/** Standard Material FAB diameter; the mini-action circles are centered within this width. */
private val FabSize = 56.dp

@Composable
fun AddEventFab(
    types: List<EventType>,
    onPick: (EventType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // Drives the overlay's enter/exit animations; the popup below stays mounted until the exit
    // animation finishes (currentState lags targetState while animating).
    val overlayVisible = remember { MutableTransitionState(false) }
    overlayVisible.targetState = expanded
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
    // Start scrolled to the bottom so the last action (nearest the FAB, e.g. Dextrose) is visible
    // immediately when the list is taller than the screen (landscape). No-op when everything fits.
    val actionsScroll = rememberScrollState()
    LaunchedEffect(expanded, actionsScroll.maxValue) {
        if (expanded) actionsScroll.scrollTo(actionsScroll.maxValue)
    }
    // Back collapses the dial instead of leaving the screen. The popup is focusable and handles
    // the key itself via onDismissRequest; this also covers the instant before it takes focus.
    BackHandler(expanded) { expanded = false }

    // One FAB definition used by both hosts below, so the icon/rotation stay continuous when the
    // button moves between the inline layer (collapsed) and the popup overlay (expanded).
    val mainFab: @Composable () -> Unit = {
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = if (expanded) "Close" else "Log event",
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // The expanded actions must never push the FAB off-screen: cap their column height to
        // the space above the FAB (matters in landscape, where all six types don't fit) and let
        // it scroll. The FAB itself stays pinned and visible.
        val actionsMaxHeight = (maxHeight - FabSize - 56.dp).coerceAtLeast(96.dp)

        // Scrim over this content area while open (tap to collapse). Rendered inline — NOT in a
        // separate popup window whose coordinate origin shifted the FAB down over the tab row — so
        // the FAB stays exactly where it is when tapped. The tab row stays bright/tappable, which
        // is fine (tapping a tab collapses the dial and navigates).
        AnimatedVisibility(visibleState = overlayVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { expanded = false },
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visibleState = overlayVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = actionsMaxHeight)
                        .verticalScroll(actionsScroll)
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    types.forEach { type ->
                        MiniFabRow(type) {
                            expanded = false
                            onPick(type)
                        }
                    }
                }
            }
            mainFab()
        }
    }
}

/** Pins a window-sized popup to the window origin — a full-screen overlay, not an anchored one. */

@Composable
private fun MiniFabRow(type: EventType, onClick: () -> Unit) {
    val color = eventColor(type.category)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 2.dp,
        ) {
            Text(
                type.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.wrapContentWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        // The circle is centered inside a FAB-width box so it sits exactly above the +/× FAB.
        Box(Modifier.width(FabSize), contentAlignment = Alignment.Center) {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = color,
                contentColor = Color.White,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(eventIcon(type.category, type.name), contentDescription = type.name)
                }
            }
        }
    }
}

/** Bottom sheet for creating or editing an event. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventEditSheet(
    types: List<EventType>,
    initialType: EventType?,
    existing: LoggedEvent?,
    onDismiss: () -> Unit,
    onSave: (type: EventType, value: Float, atMillis: Long, note: String) -> Unit,
    onDelete: ((LoggedEvent) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    // Every field is rememberSaveable so a configuration change (rotation, dark-mode toggle)
    // mid-entry can't wipe what the user typed. The time itself is already saveable inside
    // rememberTimePickerState.
    var selected by rememberSaveable(stateSaver = EventTypeSaver) {
        mutableStateOf(
            existing?.let { e -> types.firstOrNull { it.index == e.type } } ?: initialType ?: types.firstOrNull(),
        )
    }
    var amount by rememberSaveable { mutableStateOf(existing?.value?.let { trimFloat(it) } ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }

    val cal = remember { Calendar.getInstance().apply { existing?.let { timeInMillis = it.epochMillis } } }
    val timeState = rememberTimePickerState(
        initialHour = cal.get(Calendar.HOUR_OF_DAY),
        initialMinute = cal.get(Calendar.MINUTE),
        is24Hour = true,
    )
    // The day the event is logged for (time picker only covers hour/minute).
    var dayMillis by rememberSaveable { mutableStateOf(startOfDay(cal.timeInMillis)) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    // Original time/date of the event being edited, so a time-only change (now made via the
    // dialog) still counts as a pending edit and isn't silently dropped on swipe-away.
    val origHour = remember { cal.get(Calendar.HOUR_OF_DAY) }
    val origMinute = remember { cal.get(Calendar.MINUTE) }
    val origDay = remember { startOfDay(cal.timeInMillis) }

    // A Note is a special event: it can be saved with just text and no amount.
    val isNote = selected?.category == EventCategory.OTHER
    val hasAmount = amount.toFloatOrNull()?.let { it > 0f } == true
    val isValid = selected != null && (hasAmount || (isNote && note.isNotBlank()))
    val timeChanged = existing != null &&
        (timeState.hour != origHour || timeState.minute != origMinute || dayMillis != origDay)
    // A type-only change (e.g. Fast → Slow insulin) is still an edit — without this it was
    // silently discarded on swipe-away.
    val typeChanged = existing != null && selected != null && selected?.index != existing.type
    val dirty = amount != (existing?.value?.let { trimFloat(it) } ?: "") ||
        note != (existing?.note ?: "") || timeChanged || typeChanged
    val doSave: () -> Unit = {
        val type = selected
        if (type != null && isValid) {
            onSave(type, amount.toFloatOrNull() ?: 0f, atMillisFrom(dayMillis, timeState.hour, timeState.minute), note.trim())
            onDismiss()
        }
    }
    // Swiping/tapping away with unsaved input prompts to save or discard.
    val attemptDismiss: () -> Unit = { if (dirty) showDiscard = true else onDismiss() }

    ModalBottomSheet(onDismissRequest = attemptDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (existing == null) "Log event" else "Edit event",
                style = MaterialTheme.typography.headlineSmall,
            )

            // Type selector
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                types.forEach { type ->
                    androidx.compose.material3.FilterChip(
                        selected = selected == type,
                        onClick = { selected = type },
                        label = { Text(type.name) },
                        leadingIcon = {
                            Icon(eventIcon(type.category, type.name), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }

            val amountFocus = remember { FocusRequester() }
            val noteFocus = remember { FocusRequester() }
            // Adding an event opens the keyboard on the most relevant field (note for a Note event,
            // otherwise the amount). Editing focuses nothing: the time is edited via a dialog (the
            // chip below), so no text field is on screen at open and the keyboard never pops.
            LaunchedEffect(Unit) {
                if (existing == null) {
                    kotlinx.coroutines.delay(150) // let the sheet settle before focusing
                    runCatching {
                        if (initialType?.category == EventCategory.OTHER) noteFocus.requestFocus()
                        else amountFocus.requestFocus()
                    }
                }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { new -> amount = new.filter { it.isDigit() || it == '.' } },
                label = { Text("Amount" + (selected?.unit?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // Date + time are edited via dialogs (chips). Keeping the time out of an inline
                // TimeInput means no text field auto-focuses on open, so the keyboard never pops
                // just from opening the sheet (it only appears inside the time dialog, on demand).
                androidx.compose.material3.AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(dateChipLabel(dayMillis)) },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "Change date", modifier = Modifier.size(16.dp))
                    },
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.AssistChip(
                    onClick = { showTimePicker = true },
                    label = { Text(timeChipLabel(timeState.hour, timeState.minute)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Schedule, contentDescription = "Change time", modifier = Modifier.size(16.dp))
                    },
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(if (isNote) "Note" else "Note (optional)") },
                modifier = Modifier.fillMaxWidth().focusRequester(noteFocus),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existing != null && onDelete != null) {
                    // Destructive and irreversible (the engine has no undo) — confirm first.
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
                Button(
                    onClick = doSave,
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                ) { Text(if (existing == null) "Save" else "Update") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDeleteConfirm && existing != null && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this event?") },
            text = { Text("\"${existing.label.ifEmpty { "Event" }}\" will be removed from the log. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // Guard against a queued second tap re-firing the delete: duplicate rows
                    // share the engine's (time,type,value) key, so a re-fire can take a twin.
                    if (showDeleteConfirm) {
                        showDeleteConfirm = false
                        onDelete(existing)
                        onDismiss()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showDiscard) {
        // "Keep editing" must re-open the sheet: the swipe-to-dismiss that triggered this dialog
        // already animated the bottom sheet to hidden, so we re-show it rather than leave a blank.
        val keepEditing = { showDiscard = false; sheetScope.launch { sheetState.show() }; Unit }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = keepEditing,
            title = { Text("Save this event?") },
            text = { Text("You've entered some details. Save the event or discard it?") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; doSave() }, enabled = isValid) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscard = false; onDismiss() }) { Text("Discard") }
                    TextButton(onClick = keepEditing) { Text("Keep editing") }
                }
            },
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = localDayToUtc(dayMillis))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { dayMillis = utcToLocalDay(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    }

    if (showTimePicker) {
        // Time entry lives in its own dialog so the sheet has no inline auto-focusing field. The
        // keyboard here is on-demand (the user tapped the time), so it's expected, not a flicker.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = { TextButton(onClick = { showTimePicker = false }) { Text("OK") } },
            title = { Text("Time") },
            text = {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { TimeInput(state = timeState) }
            },
        )
    }
}

/**
 * Saver for the sheet's selected [EventType]: just enough fields to rebuild it after a
 * configuration change. An empty list (nothing selected) is dropped by listSaver, so restore
 * falls back to the initial-selection logic.
 */
private val EventTypeSaver = listSaver<EventType?, Any>(
    save = { t -> if (t == null) emptyList() else listOf(t.index, t.name, t.category.name) },
    restore = { l -> EventType(l[0] as Int, l[1] as String, EventCategory.valueOf(l[2] as String)) },
)

private fun timeChipLabel(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun dateChipLabel(dayMillis: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    return when (dayMillis) {
        today -> "Today"
        addDays(today, -1) -> "Yesterday"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(dayMillis))
    }
}

/** Combine the chosen day with an hour/minute. */
private fun atMillisFrom(dayMillis: Long, hour: Int, minute: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = dayMillis
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, minute)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun trimFloat(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
