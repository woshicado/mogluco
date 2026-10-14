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
package de.mogluco.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant
import de.mogluco.data.GlucoseReading
import de.mogluco.data.GlucoseUnit
import de.mogluco.data.TrendArrow
import de.mogluco.wear.MainActivity
import de.mogluco.wear.service.GlucoseSnapshot

/**
 * Watch-face complications: current value (short text / ranged value), trend arrow
 * (image), and value+arrow (short text with icon) — the restyled equivalents of the
 * legacy Juggluco complications. All tap through to the app.
 *
 * Data comes from [GlucoseSnapshot] (kept fresh by WearGlucoseService pushes; falls back
 * to a direct repository read after process recreation). A reading older than 5.5 min
 * renders as "--" (legacy Notify.glucosetimeout behavior).
 */
abstract class BaseGlucoseComplicationService : SuspendingTimelineComplicationDataSourceService() {

    /**
     * [s] as this complication shows it at [now] — the value, or "--" / the dot once the
     * reading is stale by then. [validity] is set on a live value only (see
     * [onComplicationRequest]).
     */
    protected abstract fun build(
        type: ComplicationType,
        s: GlucoseSnapshot.Snapshot,
        now: Long,
        validity: TimeRange = TimeRange.ALWAYS,
    ): ComplicationData?

    /** Sample reading for the watch-face editor. Preview data must keep the default validity. */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = System.currentTimeMillis()
        val reading = GlucoseReading(calibratedMgdl = 120.0, epochMillis = now, trend = TrendArrow.FLAT)
        return build(type, GlucoseSnapshot.Snapshot(reading, emptyList(), GlucoseUnit.MGDL, 70.0, 180.0), now)
    }

    /**
     * A live value never leaves here without its expiry. The service's own flip to "--" waits on
     * a clock that stops while the watch is suspended, and then nothing in this process runs, so
     * the staleness boundary is handed to the watch face with the data:
     *  - the value is only valid until the reading turns stale — a face stops drawing data
     *    outside its validity;
     *  - a timeline entry holds the "--" rendering from that instant on, which a face that
     *    supports timelines shows instead of an empty slot.
     * Either way the face switches by the wall clock on its own next frame; no wake-up, no
     * request to this app. A reading that is already stale is plain "--" with no expiry.
     */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val s = GlucoseSnapshot.read(this)
        val now = System.currentTimeMillis()
        val type = request.complicationType
        val usableUntil = s.usableUntilMillis?.takeIf { s.isUsable(now) }
            ?: return build(type, s, now)?.let { ComplicationDataTimeline(it, emptyList()) }
        val staleAt = Instant.ofEpochMilli(usableUntil)
        val live = build(type, s, now, TimeRange.before(staleAt)) ?: return null
        val stale = build(type, s, usableUntil + 1) ?: return null
        return ComplicationDataTimeline(
            live,
            listOf(TimelineEntry(TimeInterval(staleAt, staleAt.plusMillis(STALE_ENTRY_MILLIS)), stale)),
        )
    }

    // Tapping any glucose complication opens the app straight to the graph view.
    protected fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this,
        MainActivity.REQUEST_GRAPH,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_GRAPH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    protected fun text(value: String) = PlainComplicationText.Builder(value).build()

    protected fun arrowImage(reading: GlucoseReading?): MonochromaticImage =
        MonochromaticImage.Builder(
            Icon.createWithBitmap(ArrowBitmap.forReading(ARROW_PX, reading)),
        ).build()

    protected companion object {
        const val ARROW_PX = 64

        /**
         * How long the "--" timeline entry stays valid (a timeline entry needs an end). It is
         * replaced long before: by the next push, or by the 10-minute fallback poll.
         */
        const val STALE_ENTRY_MILLIS = 24 * 60 * 60_000L
    }
}

/** Current value: SHORT_TEXT ("124" / "6.9") or RANGED_VALUE for ring-style slots. */
class NumberComplicationService : BaseGlucoseComplicationService() {

    override fun build(
        type: ComplicationType,
        s: GlucoseSnapshot.Snapshot,
        now: Long,
        validity: TimeRange,
    ): ComplicationData? {
        val valueText = s.valueText(now)
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(valueText), text("Glucose $valueText"))
                    .setTapAction(tapAction())
                    .setValidTimeRange(validity)
                    .build()
            ComplicationType.RANGED_VALUE -> {
                // Fixed display domain (mg/dL): the ring position means the same thing on
                // every face regardless of the user's band.
                val mgdl = if (s.isUsable(now)) s.reading!!.calibratedMgdl.toFloat() else RANGE_MIN
                RangedValueComplicationData.Builder(
                    value = mgdl.coerceIn(RANGE_MIN, RANGE_MAX),
                    min = RANGE_MIN,
                    max = RANGE_MAX,
                    contentDescription = text("Glucose $valueText"),
                )
                    .setText(text(valueText))
                    .setTapAction(tapAction())
                    .setValidTimeRange(validity)
                    .build()
            }
            else -> null
        }
    }

    private companion object {
        const val RANGE_MIN = 40f
        const val RANGE_MAX = 300f
    }
}

/** Trend arrow only: MONOCHROMATIC_IMAGE (tinted by the face) or SMALL_IMAGE. */
class ArrowComplicationService : BaseGlucoseComplicationService() {

    override fun build(
        type: ComplicationType,
        s: GlucoseSnapshot.Snapshot,
        now: Long,
        validity: TimeRange,
    ): ComplicationData? {
        val reading = if (s.isUsable(now)) s.reading else null
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                androidx.wear.watchface.complications.data.MonochromaticImageComplicationData.Builder(
                    arrowImage(reading),
                    text("Glucose trend"),
                )
                    .setTapAction(tapAction())
                    .setValidTimeRange(validity)
                    .build()
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(
                        Icon.createWithBitmap(ArrowBitmap.forReading(ARROW_PX, reading)),
                        SmallImageType.ICON,
                    ).build(),
                    text("Glucose trend"),
                )
                    .setTapAction(tapAction())
                    .setValidTimeRange(validity)
                    .build()
            else -> null
        }
    }
}

/** Value + trend arrow in one SHORT_TEXT slot (icon above/beside the number). */
class NumberArrowComplicationService : BaseGlucoseComplicationService() {

    override fun build(
        type: ComplicationType,
        s: GlucoseSnapshot.Snapshot,
        now: Long,
        validity: TimeRange,
    ): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT ->
            ShortTextComplicationData.Builder(
                text(s.valueText(now)),
                text("Glucose ${s.valueText(now)} ${s.trend(now).glyph}"),
            )
                .setMonochromaticImage(arrowImage(if (s.isUsable(now)) s.reading else null))
                .setTapAction(tapAction())
                .setValidTimeRange(validity)
                .build()
        else -> null
    }
}
