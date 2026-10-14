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
package de.mogluco.ui.export

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The single export modal used by both Settings and the Calendar range export. Pick a period
 * and a format, then choose where to save; the file is written by [DataExporter] over what
 * [loadData] returns at that moment (see [loadExportData]). When [fixedWindow] is non-null
 * (calendar range select) the period chips are hidden and that exact window is exported.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(
    loadData: suspend () -> ExportData?,
    onDismiss: () -> Unit,
    fixedWindow: LongRange? = null,
    allowFullBackup: Boolean = true,
    settings: Map<String, String> = emptyMap(),
    title: String = "Export data",
) {
    // The full-database backup makes no sense for a bounded range export.
    val formats = ExportFormat.entries.filter { allowFullBackup || it != ExportFormat.DATABASE }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable: the activity is recreated if the phone rotates while the SAF picker is open, and
    // a plain remember dropped the chosen format — the returned URI was then silently ignored —
    // and reset the chosen period, so the file would have covered the wrong span. (The callers
    // keep the sheet itself open across the recreation the same way.)
    var range by rememberSaveable { mutableStateOf(ExportRange.ALL) }
    var pendingFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val now = remember { System.currentTimeMillis() }
    val window: LongRange = fixedWindow
        ?: ((now - range.days.toLong() * 24 * 60 * 60 * 1000L)..now)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val fmt = pendingFormat
        pendingFormat = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (fmt == null) {
            // The format survives rotation now that it's saveable, but if it's ever lost
            // (process death corner cases), say so rather than dropping the URI silently.
            Toast.makeText(context, "Export cancelled — please pick a format again", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        exporting = true
        // The LibreView format mirrors what the LibreView site itself would export for this
        // account: rows carry this install's LibreView device id (what its Seriennummer column
        // shows for phone uploads — not the sensor serial) and the preamble names the account
        // holder. Resolved here, once, where the engine is reachable (blank in sample mode).
        val libreSerial = runCatching { tk.glucodata.Engine.libreviewDeviceId() }.getOrDefault("")
        val createdBy = runCatching { tk.glucodata.Engine.libreviewPatientName() }.getOrDefault("")
        scope.launch {
            // Writing up to ~300k points (gzipped for backups) is multi-second work; running it
            // right here in the ActivityResult callback blocked Main and could ANR.
            // NonCancellable: the scope dies on rotation mid-write — finish the file rather than
            // leave a truncated export behind (the completion toast may be skipped, that's fine).
            // null: nothing was written, because the stored history is not loaded yet.
            val ok: Boolean? = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                runCatching {
                    // Read now, not from the composition that received the picker's result: after
                    // the activity was destroyed with the picker open that is a new view model's
                    // empty initial state, and the file was written without data.
                    val data = loadData() ?: return@withContext null
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        DataExporter.export(
                            os, fmt, data.points, data.events, data.unit, window.first, window.last,
                            data.serial, settings, libreSerial, createdBy,
                        )
                    } != null
                }.getOrDefault(false)
            }
            exporting = false
            val message = when (ok) {
                true -> "Exported ${fmt.label}"
                false -> "Export failed"
                null -> "Data is still loading — try again"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (ok == true) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)

            if (fixedWindow != null) {
                Text(
                    "Range: ${rangeLabel(fixedWindow)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Period", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportRange.entries.forEach { r ->
                        FilterChip(
                            selected = range == r,
                            onClick = { range = r },
                            label = { Text(r.label) },
                        )
                    }
                }
            }

            Text(if (exporting) "Format — exporting…" else "Format", style = MaterialTheme.typography.titleSmall)
            formats.forEach { fmt ->
                Card(
                    onClick = {
                        pendingFormat = fmt
                        launcher.launch(suggestedName(fmt))
                    },
                    // Disabled while a write is in flight so a second tap can't start a
                    // concurrent export over the same data.
                    enabled = !exporting,
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FileDownload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fmt.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                fmt.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun suggestedName(format: ExportFormat): String {
    if (format == ExportFormat.LIBREVIEW) {
        // Mirror LibreView's own download name: <initials>_glucose_<d-M-yyyy>.csv (no leading
        // zeros), e.g. JM_glucose_1-9-2026.csv.
        val initials = runCatching { tk.glucodata.Engine.libreviewPatientName() }.getOrDefault("")
            .split(" ").filter { it.isNotBlank() }.joinToString("") { it.first().uppercase() }
        val d = SimpleDateFormat("d-M-yyyy", Locale.US).format(Date())
        return "${initials.ifEmpty { "Mogluco" }}_glucose_$d.csv"
    }
    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
    val prefix = if (format == ExportFormat.DATABASE) "Mogluco-backup" else "Mogluco-data"
    return "$prefix-$stamp.${format.ext}"
}

private fun rangeLabel(window: LongRange): String {
    val f = SimpleDateFormat("d MMM", Locale.getDefault())
    return "${f.format(Date(window.first))} – ${f.format(Date(window.last))}"
}
