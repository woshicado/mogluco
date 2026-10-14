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
package de.mogluco.wear.tile

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import de.mogluco.data.glucoseState
import de.mogluco.data.windowed
import de.mogluco.notification.TrendGraphRenderer
import de.mogluco.ui.theme.DarkGlucoseColors
import de.mogluco.ui.theme.colorFor
import de.mogluco.wear.MainActivity
import de.mogluco.wear.complication.ArrowBitmap
import de.mogluco.wear.service.GlucoseSnapshot
import de.mogluco.wear.ui.home.ageText

/**
 * Swipe-accessible glucose tile: state-colored value + trend glyph, reading age, and the
 * shared mini trend graph (last 3 h). Refreshed by [de.mogluco.wear.service.WearGlucoseService]
 * pushes on each reading (plus a 60 s freshness hint for the ager line). A live value carries
 * its expiry, so the system itself falls back to the stale rendering when nothing refreshes it.
 */
class GlucoseTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        val snapshot = runBlocking { GlucoseSnapshot.read(this@GlucoseTileService) }
        val now = System.currentTimeMillis()
        val timeline = TimelineBuilders.Timeline.Builder()
        // A live value is only valid until its reading turns stale; from then on the system
        // shows the default entry below ("--", grey dot) by itself. It switches on the wall clock
        // without waking the watch or asking this app, so a tile that was cached while the watch
        // slept never comes up with an old value as the current one.
        val usableUntil = snapshot.usableUntilMillis?.takeIf { snapshot.isUsable(now) }
        if (usableUntil != null) {
            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(Layout.Builder().setRoot(layout(snapshot, now)).build())
                    .setValidity(
                        TimelineBuilders.TimeInterval.Builder().setEndMillis(usableUntil + 1).build(),
                    )
                    .build(),
            )
        }
        // No validity = the default entry: the stale rendering, or the tile as it is right now
        // when there is no live value to begin with.
        val fallback = if (usableUntil != null) {
            layout(snapshot, usableUntil + 1, shownLater = true)
        } else {
            layout(snapshot, now)
        }
        timeline.addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setLayout(Layout.Builder().setRoot(fallback).build())
                .build(),
        )
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion(snapshot, now))
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(timeline.build())
            .build()
        completer.set(tile)
        "tileRequest"
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        val snapshot = runBlocking { GlucoseSnapshot.read(this@GlucoseTileService) }
        val builder = ResourceBuilders.Resources.Builder()
            // Answer for the version that was asked for: the one derived here could already
            // differ (a reading landed or went stale between the two requests).
            .setVersion(requestParams.version)
        chartBitmap(snapshot)?.let { bmp ->
            builder.addIdToImageMapping(CHART_ID, inlineImage(bmp))
        }
        // Both arrows always: the live and the stale entry of one tile share this resource set.
        builder.addIdToImageMapping(ARROW_ID, inlineImage(arrowBitmap(snapshot)))
        builder.addIdToImageMapping(
            ARROW_STALE_ID,
            inlineImage(ArrowBitmap.forReading(ARROW_PX, null, DarkGlucoseColors.stale.toArgb())),
        )
        completer.set(builder.build())
        "tileResources"
    }

    /**
     * Resources change whenever a new reading lands, and when it turns stale (the arrow becomes
     * the grey dot) — key the version to the reading's timestamp and its usability.
     */
    private fun resourcesVersion(snapshot: GlucoseSnapshot.Snapshot, now: Long): String =
        "${snapshot.reading?.epochMillis ?: 0L}-${snapshot.isUsable(now)}"

    /**
     * The tile as it reads at [now] — the stale rendering when [now] is past the reading's window.
     * [shownLater]: the system shows this layout from [now] on, for as long as nothing refreshes
     * the tile, so its age line can only state a lower bound.
     */
    private fun layout(
        snapshot: GlucoseSnapshot.Snapshot,
        now: Long,
        shownLater: Boolean = false,
    ): LayoutElementBuilders.LayoutElement {
        val valueColor = stateColorArgb(snapshot, now)
        val secondary = AColor.argb(255, 190, 201, 199)

        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            // The tile previews the graph, so tapping it opens the graph view.
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_DESTINATION,
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(MainActivity.DEST_GRAPH)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .addContent(
                // Value + the shared drawn trend arrow (rotated by rate), matching the complications
                // and the phone/graph arrows — not the old flat Unicode glyph.
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        Text.Builder()
                            .setText(snapshot.valueText(now))
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(androidx.wear.protolayout.DimensionBuilders.sp(32f))
                                    .setWeight(FONT_WEIGHT_BOLD)
                                    .setColor(argb(valueColor))
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(
                        Image.Builder()
                            .setResourceId(if (snapshot.isUsable(now)) ARROW_ID else ARROW_STALE_ID)
                            .setWidth(dp(ARROW_DP))
                            .setHeight(dp(ARROW_DP))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                Text.Builder()
                    .setText(subtitle(snapshot, now, shownLater))
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(androidx.wear.protolayout.DimensionBuilders.sp(12f))
                            .setColor(argb(secondary))
                            .build(),
                    )
                    .build(),
            )

        if (hasChartData(snapshot)) {
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            column.addContent(
                Image.Builder()
                    .setResourceId(CHART_ID)
                    .setWidth(dp(CHART_W_DP))
                    .setHeight(dp(CHART_H_DP))
                    .build(),
            )
        }

        // Center the stack on both axes so it isn't pushed to a corner, and sits as one group.
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()
    }

    private fun subtitle(snapshot: GlucoseSnapshot.Snapshot, now: Long, shownLater: Boolean): String {
        val r = snapshot.reading ?: return "Waiting for data"
        val age = ageText(r.ageMillis(now))
        return "${snapshot.unit.label} · ${if (shownLater) "over $age" else age}"
    }

    /** Cheap layout-side predicate matching lineGraph's null condition (≥2 points in window). */
    private fun hasChartData(snapshot: GlucoseSnapshot.Snapshot): Boolean {
        val now = System.currentTimeMillis()
        return snapshot.history.windowed(now - CHART_WINDOW_MS, now).size >= 2
    }

    /** State color (in-range/high/low/urgent) for the value + arrow, or stale grey when not usable. */
    private fun stateColorArgb(snapshot: GlucoseSnapshot.Snapshot, now: Long): Int =
        if (snapshot.isUsable(now)) {
            DarkGlucoseColors.colorFor(
                glucoseState(snapshot.reading!!.calibratedMgdl, snapshot.targetLow, snapshot.targetHigh),
            ).toArgb()
        } else {
            DarkGlucoseColors.stale.toArgb()
        }

    /** The shared drawn trend arrow, rotated by rate and tinted to the state color (dot when stale). */
    private fun arrowBitmap(snapshot: GlucoseSnapshot.Snapshot): Bitmap {
        val now = System.currentTimeMillis()
        val reading = if (snapshot.isUsable(now)) snapshot.reading else null
        return ArrowBitmap.forReading(ARROW_PX, reading, stateColorArgb(snapshot, now))
    }

    private fun chartBitmap(snapshot: GlucoseSnapshot.Snapshot): Bitmap? =
        TrendGraphRenderer.lineGraph(
            history = snapshot.history,
            nowMillis = System.currentTimeMillis(),
            windowMillis = CHART_WINDOW_MS,
            widthPx = CHART_W_PX,
            heightPx = CHART_H_PX,
            padPx = 6f,
            strokePx = 4f,
            dotPx = 6f,
            gridStrokePx = 1.5f,
            // Show the user's in-range band (low..high) behind the trace, matching the graph screen.
            targetLow = snapshot.targetLow,
            targetHigh = snapshot.targetHigh,
            bandColor = DarkGlucoseColors.inRange.copy(alpha = 0.22f).toArgb(),
            gapMillis = de.mogluco.wear.WearApp.from(this).preferences.gapThresholdMinutes.value * 60_000L,
        )

    private fun inlineImage(bitmap: Bitmap): ResourceBuilders.ImageResource {
        // TrendGraphRenderer already produces ARGB_8888; only convert if that ever changes.
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val buffer = ByteBuffer.allocate(argb.byteCount)
        argb.copyPixelsToBuffer(buffer)
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(buffer.array())
                    .setWidthPx(argb.width)
                    .setHeightPx(argb.height)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_ARGB_8888)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val CHART_ID = "chart"
        const val ARROW_ID = "arrow"
        const val ARROW_STALE_ID = "arrow_stale"
        const val ARROW_PX = 52
        const val ARROW_DP = 24f
        const val CHART_WINDOW_MS = 3 * 60 * 60 * 1000L
        // Readable but compact so value + arrow + age + graph fit one screen without scrolling.
        const val CHART_W_PX = 340
        const val CHART_H_PX = 132
        const val CHART_W_DP = 154f
        const val CHART_H_DP = 60f
    }
}
