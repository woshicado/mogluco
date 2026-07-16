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
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import de.mogluco.data.GlucoseReading
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
abstract class BaseGlucoseComplicationService : SuspendingComplicationDataSourceService() {

    // Tapping any glucose complication opens the app straight to the graph view.
    protected fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
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

    /** Sample reading for the watch-face editor preview. */
    protected fun previewReading() = GlucoseReading(
        calibratedMgdl = 120.0,
        epochMillis = System.currentTimeMillis(),
        trend = TrendArrow.FLAT,
    )

    protected companion object {
        const val ARROW_PX = 64
    }
}

/** Current value: SHORT_TEXT ("124" / "6.9") or RANGED_VALUE for ring-style slots. */
class NumberComplicationService : BaseGlucoseComplicationService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, GlucoseSnapshot.Snapshot(previewReading(), emptyList(), de.mogluco.data.GlucoseUnit.MGDL, 70.0, 180.0))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, GlucoseSnapshot.read(this))

    private fun build(type: ComplicationType, s: GlucoseSnapshot.Snapshot): ComplicationData? {
        val valueText = s.valueText()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(valueText), text("Glucose $valueText"))
                    .setTapAction(tapAction())
                    .build()
            ComplicationType.RANGED_VALUE -> {
                // Fixed display domain (mg/dL): the ring position means the same thing on
                // every face regardless of the user's band.
                val mgdl = if (s.isUsable()) s.reading!!.calibratedMgdl.toFloat() else RANGE_MIN
                RangedValueComplicationData.Builder(
                    value = mgdl.coerceIn(RANGE_MIN, RANGE_MAX),
                    min = RANGE_MIN,
                    max = RANGE_MAX,
                    contentDescription = text("Glucose $valueText"),
                )
                    .setText(text(valueText))
                    .setTapAction(tapAction())
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

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, previewReading())

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val s = GlucoseSnapshot.read(this)
        return build(request.complicationType, if (s.isUsable()) s.reading else null)
    }

    private fun build(type: ComplicationType, reading: GlucoseReading?): ComplicationData? = when (type) {
        ComplicationType.MONOCHROMATIC_IMAGE ->
            androidx.wear.watchface.complications.data.MonochromaticImageComplicationData.Builder(
                arrowImage(reading),
                text("Glucose trend"),
            )
                .setTapAction(tapAction())
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
                .build()
        else -> null
    }
}

/** Value + trend arrow in one SHORT_TEXT slot (icon above/beside the number). */
class NumberArrowComplicationService : BaseGlucoseComplicationService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, GlucoseSnapshot.Snapshot(previewReading(), emptyList(), de.mogluco.data.GlucoseUnit.MGDL, 70.0, 180.0))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, GlucoseSnapshot.read(this))

    private fun build(type: ComplicationType, s: GlucoseSnapshot.Snapshot): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT ->
            ShortTextComplicationData.Builder(
                text(s.valueText()),
                text("Glucose ${s.valueText()} ${s.trend().glyph}"),
            )
                .setMonochromaticImage(arrowImage(if (s.isUsable()) s.reading else null))
                .setTapAction(tapAction())
                .build()
        else -> null
    }
}
