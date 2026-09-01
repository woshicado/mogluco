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
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.LoggedEvent
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the LibreView export to the byte format of a real LibreView CSV download (matched against
 * an actual account export): two-line preamble, the exact 21 German column headers, CRLF line
 * endings, dd-MM-yyyy HH:mm timestamps, record-type blocks, German-comma quoted insulin doses,
 * plain-integer carb grams.
 */
class LibreViewExportTest {

    // Verbatim header line 2 of a LibreView download (German account locale).
    private val referenceHeader =
        "Gerät,Seriennummer,Gerätezeitstempel,Aufzeichnungstyp,Glukosewert-Verlauf mg/dL," +
            "Glukose-Scan mg/dL,Nicht numerisches schnellwirkendes Insulin," +
            "Schnellwirkendes Insulin (Einheiten),Nicht numerische Nahrungsdaten," +
            "Kohlenhydrate (Gramm),Kohlenhydrate (Portionen),Nicht numerisches Depotinsulin," +
            "Depotinsulin (Einheiten),Notizen,Glukose-Teststreifen mg/dL,Keton mmol/L," +
            "Mahlzeiteninsulin (Einheiten),Korrekturinsulin (Einheiten)," +
            "Insulin-Änderung durch Anwender (Einheiten),Zurückliegender Ketonwert mmol/L," +
            "Scan-Ketonwert mmol/L"

    private val serial = "75e234d5-a8f4-84da-a441-47436a794bd0"

    private fun at(stamp: String): Long =
        SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).parse(stamp)!!.time

    private fun export(
        points: List<GlucosePoint> = emptyList(),
        events: List<LoggedEvent> = emptyList(),
        unit: GlucoseUnit = GlucoseUnit.MGDL,
    ): List<String> {
        val out = ByteArrayOutputStream()
        DataExporter.export(
            out, ExportFormat.LIBREVIEW, points, events, unit,
            windowStart = 0L, windowEnd = Long.MAX_VALUE,
            libreSerial = serial, createdBy = "J M",
        )
        val text = out.toString("UTF-8")
        assertTrue("file must end with CRLF", text.endsWith("\r\n"))
        // Every line break is CRLF — no bare \n anywhere.
        assertEquals(-1, text.replace("\r\n", "").indexOf('\n'))
        return text.split("\r\n").dropLast(1)
    }

    private fun event(
        stamp: String,
        category: EventCategory,
        value: Float,
        label: String = "",
        note: String = "",
    ) = LoggedEvent(at(stamp), type = 0, value = value, note = note, category = category, label = label)

    @Test
    fun `preamble and header match the LibreView download`() {
        val lines = export()
        val expectedStamp = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        assertEquals("Glukose-Werte,Erstellt am,$expectedStamp UTC,Erstellt von,J M", lines[0])
        assertEquals(referenceHeader, lines[1])
        assertEquals(21, referenceHeader.split(",").size)
    }

    @Test
    fun `glucose insulin carbs blood and notes land in their columns and record types`() {
        val lines = export(
            points = listOf(GlucosePoint(at("19-08-2026 02:10"), rawMgdl = 100.0, calibratedMgdl = 103.4)),
            events = listOf(
                event("19-08-2026 02:15", EventCategory.INSULIN, 1.5f, label = "Fast insulin"),
                event("19-08-2026 06:49", EventCategory.INSULIN, 11f, label = "Slow insulin"),
                event("19-08-2026 06:50", EventCategory.CARBS, 40f, label = "Carbohydrates"),
                event("19-08-2026 07:00", EventCategory.BLOOD, 95f, label = "Blood glucose"),
                event("19-08-2026 08:00", EventCategory.ACTIVITY, 30f, label = "Activity"),
            ),
        )
        val rows = lines.drop(2)
        assertEquals(
            listOf(
                "FreeStyle Libre 3,$serial,19-08-2026 02:10,0,103,,,,,,,,,,,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 07:00,2,,,,,,,,,,,95,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 02:15,4,,,,\"1,5\",,,,,,,,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 06:49,4,,,,,,,,,\"11,0\",,,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 06:50,5,,,,,,40,,,,,,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 08:00,6,,,,,,,,,,Activity 30,,,,,,,",
            ),
            rows,
        )
    }

    @Test
    fun `free-text notes on typed events become companion type-6 rows`() {
        val lines = export(
            events = listOf(
                event("19-08-2026 06:49", EventCategory.INSULIN, 4f, label = "Fast insulin", note = "before breakfast"),
            ),
        )
        val rows = lines.drop(2)
        assertEquals(
            listOf(
                "FreeStyle Libre 3,$serial,19-08-2026 06:49,4,,,,\"4,0\",,,,,,,,,,,,,",
                "FreeStyle Libre 3,$serial,19-08-2026 06:49,6,,,,,,,,,,before breakfast,,,,,,,",
            ),
            rows,
        )
    }

    @Test
    fun `mmol blood values convert to the mg-dL strip column`() {
        val lines = export(
            events = listOf(event("19-08-2026 07:00", EventCategory.BLOOD, 5.5f)),
            unit = GlucoseUnit.MMOLL,
        )
        // 5.5 mmol/L * 18.0182 = 99.1 → 99 mg/dL.
        assertEquals("FreeStyle Libre 3,$serial,19-08-2026 07:00,2,,,,,,,,,,,99,,,,,,", lines.drop(2).single())
    }
}
