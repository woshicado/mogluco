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
import de.mogluco.data.EventRepository
import de.mogluco.data.isSlowInsulinLabel
import de.mogluco.data.unit
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
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
    LIBREVIEW("LibreView (CSV)", "Exact LibreView download format — as if exported from the site.", "csv", "text/csv"),
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
 * What an export writes, as the repositories hold it at write time: the readings and events, the
 * unit they are written in and the serial of the sensor in use ("" = none).
 */
class ExportData(
    val points: List<GlucosePoint>,
    val events: List<LoggedEvent>,
    val unit: GlucoseUnit,
    val serial: String,
)

/**
 * Reads what an export writes straight from the repositories, at the moment the file is written
 * — not from a screen's collected state. A view model created while the file picker was open
 * (process death, "Don't keep activities") still holds its initial state when the picked file
 * comes back: empty lists, the default unit. The export then wrote a file without data — or one
 * in the wrong unit — and reported success.
 *
 * @return null while the stored readings are not loaded yet: the caller writes nothing and says
 * so. A store that really holds no data is not that case — its (empty) lists are returned.
 */
suspend fun loadExportData(glucose: GlucoseRepository, events: EventRepository): ExportData? {
    val (points, logged) = loadedLists(glucose.history, glucose.historyLoaded, events.recent) ?: return null
    return ExportData(points, logged, glucose.unit.first(), glucose.currentSensor?.id.orEmpty())
}

/**
 * [history] and [events] once [historyLoaded] says the stored readings are in — waited for up to
 * [timeoutMillis] (a process that has only just started is still reading them), null if they
 * are not by then. A history that already holds something is no proof of that: a live reading
 * can land before the stored ones do. The events need no such wait: both event repositories
 * fill their list when they are created.
 */
internal suspend fun loadedLists(
    history: Flow<List<GlucosePoint>>,
    historyLoaded: Flow<Boolean>,
    events: Flow<List<LoggedEvent>>,
    timeoutMillis: Long = HISTORY_LOAD_TIMEOUT_MS,
): Pair<List<GlucosePoint>, List<LoggedEvent>>? {
    withTimeoutOrNull(timeoutMillis) { historyLoaded.firstOrNull { it } } ?: return null
    return history.first() to events.first()
}

/** How long an export waits for the stored history before it gives up without writing. */
private const val HISTORY_LOAD_TIMEOUT_MS = 15_000L

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
        libreSerial: String = "",
        createdBy: String = "",
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
                ExportFormat.LIBREVIEW ->
                    writeLibreView(w, pts, evs, unit, libreSerial.ifBlank { serial }, createdBy)
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

    // The 21 columns of a real LibreView export, verbatim (German — the layout LibreView serves
    // for this account; parsers key on column POSITION, and the wording must match the site's
    // download byte-for-byte so this file can stand in for one).
    private const val LV_HEADER =
        "Gerät,Seriennummer,Gerätezeitstempel,Aufzeichnungstyp,Glukosewert-Verlauf mg/dL," +
            "Glukose-Scan mg/dL,Nicht numerisches schnellwirkendes Insulin," +
            "Schnellwirkendes Insulin (Einheiten),Nicht numerische Nahrungsdaten," +
            "Kohlenhydrate (Gramm),Kohlenhydrate (Portionen),Nicht numerisches Depotinsulin," +
            "Depotinsulin (Einheiten),Notizen,Glukose-Teststreifen mg/dL,Keton mmol/L," +
            "Mahlzeiteninsulin (Einheiten),Korrekturinsulin (Einheiten)," +
            "Insulin-Änderung durch Anwender (Einheiten),Zurückliegender Ketonwert mmol/L," +
            "Scan-Ketonwert mmol/L"
    private const val LV_COLS = 21

    // Column indices (0-based) in LV_HEADER.
    private const val LV_DEVICE = 0
    private const val LV_SERIAL = 1
    private const val LV_TIMESTAMP = 2
    private const val LV_RECORD_TYPE = 3
    private const val LV_HISTORIC = 4
    private const val LV_RAPID_UNITS = 7
    private const val LV_CARB_GRAMS = 9
    private const val LV_LONG_UNITS = 12
    private const val LV_NOTES = 13
    private const val LV_STRIP = 14

    /**
     * Byte-for-byte replica of a LibreView CSV download, so the file can be used wherever a real
     * LibreView export would be — without uploading first. Matched against an actual account
     * export: two-line preamble ("Glukose-Werte,Erstellt am,<UTC time> UTC,Erstellt von,<name>"
     * then the 21 German column headers), CRLF line endings, `dd-MM-yyyy HH:mm` local timestamps,
     * rows grouped in ascending Record Type blocks each sorted by time, insulin units as
     * one-decimal German comma numbers (hence quoted), carbs as plain gram integers.
     *
     * Record types: 0 historic glucose (mg/dL), 2 strip blood glucose, 4 insulin (rapid in its
     * column, long/Depot in its), 5 food carbs, 6 note text. LibreView never puts Notizen on a
     * type-4/5 row, so an event's free-text note is emitted as a companion type-6 row — exactly
     * how a note logged in the official app appears.
     *
     * The glucose value is the sensor's reading ([GlucosePoint.rawMgdl]), never the app's
     * smoothed trace: that one lags and changes with a display setting, and this file stands in
     * for the site's download.
     *
     * @param serial the LibreView device identifier of this install — the value the Seriennummer
     *   column carries for phone-app uploads in a real export (not the sensor's serial).
     */
    private fun writeLibreView(
        w: Appendable,
        pts: List<GlucosePoint>,
        evs: List<LoggedEvent>,
        unit: GlucoseUnit,
        serial: String,
        createdBy: String,
    ) {
        val lvFmt = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)
        val utcFmt = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        fun line(s: String) = w.append(s).append("\r\n")
        line("Glukose-Werte,Erstellt am,${utcFmt.format(Date())} UTC,Erstellt von,${escape(deFormula(createdBy), ",")}")
        line(LV_HEADER)

        // One-decimal comma ("4,0" / "1,5") exactly as LibreView renders insulin doses; the comma
        // makes escape() quote the field, matching the real file.
        fun germanUnits(v: Float) = String.format(Locale.GERMANY, "%.1f", v)
        fun row(time: Long, recordType: Int, fill: (Array<String>) -> Unit) {
            val f = Array(LV_COLS) { "" }
            f[LV_DEVICE] = "FreeStyle Libre 3"
            f[LV_SERIAL] = serial
            f[LV_TIMESTAMP] = lvFmt.format(Date(time))
            f[LV_RECORD_TYPE] = recordType.toString()
            fill(f)
            // Notizen is the only free-text (user-written) column — formula-injection hardened.
            f[LV_NOTES] = deFormula(f[LV_NOTES])
            line(f.joinToString(",") { escape(it, ",") })
        }

        // A real export is written in ascending Record Type blocks, each time-sorted. Collect the
        // note rows (own note events + free-text companions of typed rows) to sort them together.
        pts.forEach { p ->
            row(p.epochMillis, 0) { it[LV_HISTORIC] = p.rawMgdl.roundToInt().toString() }
        }
        val notes = mutableListOf<Pair<Long, String>>()
        fun noteFor(e: LoggedEvent) {
            if (e.note.isNotEmpty()) notes += e.epochMillis to e.note
        }
        evs.filter { it.category == EventCategory.BLOOD }.forEach { e ->
            // Blood values are entered in the display unit; the column is mg/dL.
            val mgdl = if (unit == GlucoseUnit.MMOLL) e.value * 18.0182 else e.value.toDouble()
            row(e.epochMillis, 2) { it[LV_STRIP] = mgdl.roundToInt().toString() }
            noteFor(e)
        }
        evs.filter { it.category == EventCategory.INSULIN }.forEach { e ->
            row(e.epochMillis, 4) {
                it[if (isSlowInsulinLabel(e.label)) LV_LONG_UNITS else LV_RAPID_UNITS] = germanUnits(e.value)
            }
            noteFor(e)
        }
        evs.filter { it.category == EventCategory.CARBS }.forEach { e ->
            row(e.epochMillis, 5) {
                it[LV_CARB_GRAMS] = if (e.value == e.value.toLong().toFloat()) {
                    e.value.toLong().toString()
                } else {
                    germanUnits(e.value)
                }
            }
            noteFor(e)
        }
        evs.filter { it.category == EventCategory.ACTIVITY || it.category == EventCategory.OTHER }.forEach { e ->
            notes += e.epochMillis to
                listOf(e.label, trim(e.value), e.note).filter { s -> s.isNotEmpty() }.joinToString(" ")
        }
        notes.sortedBy { it.first }.forEach { (t, text) ->
            row(t, 6) { it[LV_NOTES] = text }
        }
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
