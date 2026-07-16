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

import de.mogluco.data.EventCategory
import de.mogluco.data.unit
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/** What kind of file the export produces. */
enum class ExportFormat(val label: String, val description: String, val ext: String, val mime: String) {
    CSV("CSV", "Comma-separated — opens in any spreadsheet.", "csv", "text/csv"),
    TSV("TSV", "Tab-separated values.", "tsv", "text/tab-separated-values"),
    LIBREVIEW("LibreView (CSV)", "Abbott column layout for sharing with a clinic.", "csv", "text/csv"),
    DATABASE("Full backup", "Compressed backup of all data + settings, re-importable.", "jbak", "application/octet-stream"),
}

/** A day-range preset for the export window. */
enum class ExportRange(val label: String, val days: Int) {
    DAY("1 day", 1),
    WEEK("7 days", 7),
    FORTNIGHT("14 days", 14),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    ALL("All", 100000),
}

/** A restored backup. */
data class Backup(
    val settings: Map<String, String>,
    val events: List<LoggedEvent>,
    val glucose: List<GlucosePoint>,
)

/**
 * Writes glucose readings and logged events. Plain Kotlin over in-memory data, so it works
 * today against the sample data (and the native store once that path is unblocked).
 */
object DataExporter {

    fun export(
        out: OutputStream,
        format: ExportFormat,
        points: List<GlucosePoint>,
        events: List<LoggedEvent>,
        unit: GlucoseUnit,
        windowStart: Long,
        windowEnd: Long,
        serial: String = "",
        settings: Map<String, String> = emptyMap(),
    ) {
        if (format == ExportFormat.DATABASE) {
            // Full backup ignores the window — it's everything — and is gzip-compressed.
            writeBackup(out, settings, points, events, unit)
            return
        }
        val pts = points.filter { it.epochMillis in windowStart..windowEnd }.sortedBy { it.epochMillis }
        val evs = events.filter { it.epochMillis in windowStart..windowEnd }.sortedBy { it.epochMillis }
        out.bufferedWriter().use { w ->
            when (format) {
                ExportFormat.CSV -> writeDelimited(w, pts, evs, unit, ",")
                ExportFormat.TSV -> writeDelimited(w, pts, evs, unit, "\t")
                ExportFormat.LIBREVIEW -> writeLibreView(w, pts, evs, serial)
                ExportFormat.DATABASE -> {}
            }
        }
    }

    private fun writeDelimited(
        w: Appendable,
        pts: List<GlucosePoint>,
        evs: List<LoggedEvent>,
        unit: GlucoseUnit,
        sep: String,
    ) {
        // One formatter per export call — the old `get() =` property allocated a SimpleDateFormat
        // per row. XXX appends the UTC offset: zone-less local wall time can't be re-anchored
        // once the file leaves the phone (clinic in another zone, travel, DST).
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        // Exported numbers are ALWAYS Locale.US-formatted: GlucoseUnit.format follows the device
        // locale, and a decimal comma (de/nl/fr/es) corrupts numeric columns — fatally so in CSV,
        // where it doubles as the separator. On-screen formatting stays localized.
        fun num(mgdl: Double): String = when (unit) {
            GlucoseUnit.MGDL -> unit.format(mgdl) // integer, locale-independent
            GlucoseUnit.MMOLL -> String.format(Locale.US, "%.1f", unit.fromMgdl(mgdl))
        }
        val u = unit.label
        w.appendLine(listOf("time", "type", "smoothed ($u)", "raw ($u)", "value", "note").joinToString(sep))
        pts.forEach { p ->
            w.appendLine(
                listOf(
                    isoFmt.format(Date(p.epochMillis)), "glucose",
                    num(p.calibratedMgdl), num(p.rawMgdl), "", "",
                ).joinToString(sep) { escape(it, sep) },
            )
        }
        evs.forEach { e ->
            w.appendLine(
                listOf(
                    isoFmt.format(Date(e.epochMillis)), deFormula(e.label.ifEmpty { e.category.name.lowercase() }),
                    "", "", deFormula(trim(e.value) + e.category.unit), deFormula(e.note),
                ).joinToString(sep) { escape(it, sep) },
            )
        }
    }

    /**
     * Abbott / LibreView column layout. Each entry goes in its correctly-named column with an
     * Abbott Record Type (0 historic glucose, 1 scan, 4 insulin, 5 food/carbs, 6 notes) rather
     * than everything as type 6. Glucose is written in mg/dL to match the column headers. The
     * serial number is filled from the active sensor.
     *
     * Note: this mirrors LibreView's *export* layout for spreadsheet/clinic review; LibreView
     * itself has no CSV import, so a clinician opens it in their software/Excel.
     */
    private fun writeLibreView(w: Appendable, pts: List<GlucosePoint>, evs: List<LoggedEvent>, serial: String) {
        // Hoisted per export call — the old `get() =` property allocated one per row.
        val lvFmt = SimpleDateFormat("MM-dd-yyyy HH:mm", Locale.US)
        val cols = listOf(
            "Device", "Serial Number", "Device Timestamp", "Record Type",
            "Historic Glucose mg/dL", "Scan Glucose mg/dL",
            "Non-numeric Rapid-Acting Insulin", "Rapid-Acting Insulin (units)",
            "Non-numeric Food", "Carbohydrates (grams)", "Carbohydrates (servings)",
            "Non-numeric Long-Acting Insulin", "Long-Acting Insulin (units)", "Notes",
        )
        w.appendLine(cols.joinToString(","))

        pts.forEach { p ->
            // recordType 0 = historic glucose
            rowAt(w, cols, lvFmt, p.epochMillis, serial, 0) { it["Historic Glucose mg/dL"] = p.calibratedMgdl.roundToInt().toString() }
        }
        evs.forEach { e ->
            when (e.category) {
                EventCategory.CARBS -> rowAt(w, cols, lvFmt, e.epochMillis, serial, 5) {
                    it["Carbohydrates (grams)"] = trim(e.value); it["Notes"] = e.note
                }
                EventCategory.INSULIN -> {
                    val slow = e.label.lowercase().let { l -> "slow" in l || "long" in l || "basal" in l }
                    rowAt(w, cols, lvFmt, e.epochMillis, serial, 4) {
                        if (slow) it["Long-Acting Insulin (units)"] = trim(e.value)
                        else it["Rapid-Acting Insulin (units)"] = trim(e.value)
                        it["Notes"] = e.note
                    }
                }
                else -> rowAt(w, cols, lvFmt, e.epochMillis, serial, 6) {
                    it["Notes"] = listOf(e.label, trim(e.value), e.note).filter { s -> s.isNotEmpty() }.joinToString(" ")
                }
            }
        }
    }

    private fun rowAt(
        w: Appendable,
        cols: List<String>,
        fmt: SimpleDateFormat,
        time: Long,
        serial: String,
        recordType: Int,
        fill: (MutableMap<String, String>) -> Unit,
    ) {
        val m = linkedMapOf<String, String>().apply { cols.forEach { put(it, "") } }
        m["Device"] = "Mogluco"
        m["Serial Number"] = serial
        m["Device Timestamp"] = fmt.format(Date(time))
        m["Record Type"] = recordType.toString()
        fill(m)
        // Notes is the only free-text (user-written) column — harden it against formula injection.
        m["Notes"] = deFormula(m["Notes"].orEmpty())
        w.appendLine(cols.joinToString(",") { escape(m[it].orEmpty(), ",") })
    }

    // --- Backup (gzip) + import ---

    private fun writeBackup(
        out: OutputStream,
        settings: Map<String, String>,
        pts: List<GlucosePoint>,
        evs: List<LoggedEvent>,
        unit: GlucoseUnit,
    ) {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("unit", unit.name)
        root.put("settings", JSONObject(settings as Map<*, *>))
        root.put("glucose", JSONArray().apply {
            pts.forEach {
                put(JSONObject().put("t", it.epochMillis).put("cal", it.calibratedMgdl).put("raw", it.rawMgdl))
            }
        })
        root.put("events", JSONArray().apply {
            evs.forEach {
                put(
                    JSONObject().put("t", it.epochMillis).put("type", it.type).put("value", it.value.toDouble())
                        .put("note", it.note).put("label", it.label).put("category", it.category.name),
                )
            }
        })
        GZIPOutputStream(out).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
    }

    fun readBackup(input: InputStream): Backup {
        val text = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).readText()
        val root = JSONObject(text)
        // Refuse backups from a NEWER schema instead of half-importing whatever fields happen to
        // still parse; the caller's runCatching turns this into the standard failure toast.
        val version = root.optInt("version", 1)
        require(version <= BACKUP_VERSION) { "Backup version $version is newer than this app understands" }
        val settings = mutableMapOf<String, String>()
        root.optJSONObject("settings")?.let { s ->
            s.keys().forEach { k -> settings[k] = s.get(k).toString() }
        }
        val events = mutableListOf<LoggedEvent>()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val category = runCatching { EventCategory.valueOf(o.optString("category", "OTHER")) }
                    .getOrDefault(EventCategory.OTHER)
                events += LoggedEvent(
                    epochMillis = o.getLong("t"),
                    type = o.optInt("type", 0),
                    value = o.optDouble("value", 0.0).toFloat(),
                    note = o.optString("note", ""),
                    category = category,
                    label = o.optString("label", ""),
                )
            }
        }
        val glucose = mutableListOf<GlucosePoint>()
        root.optJSONArray("glucose")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // optDouble defaults to NaN when the field is missing/non-numeric — skip such
                // points instead of importing NaN into the chart/statistics.
                val raw = o.optDouble("raw")
                val cal = o.optDouble("cal")
                if (!raw.isFinite() || !cal.isFinite()) continue
                // Named args so raw/calibrated can't be transposed (GlucosePoint is raw-first).
                glucose += GlucosePoint(
                    epochMillis = o.getLong("t"),
                    rawMgdl = raw,
                    calibratedMgdl = cal,
                )
            }
        }
        return Backup(settings, events, glucose)
    }

    private fun escape(s: String, sep: String): String =
        if (s.contains(sep) || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    /**
     * OWASP CSV-injection hardening: spreadsheets execute a cell starting with '=', '+', '-' or
     * '@' as a formula, so a crafted event note could exfiltrate data when the export is opened
     * in Excel/Sheets. A leading single quote makes it render as plain text. Only for the
     * CSV/TSV paths — the JSON backup must round-trip the text verbatim.
     */
    private fun deFormula(s: String): String =
        if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s

    private fun trim(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    /** Schema version written to backups; readBackup rejects anything newer. */
    private const val BACKUP_VERSION = 2
}
