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
package de.mogluco.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import de.mogluco.ui.theme.DarkGlucoseColors
import de.mogluco.ui.theme.GlucoseColors

/**
 * Watch theme: the phone's brand teal on the watch's always-dark surfaces, with the SAME
 * glucose-semantic tokens ([DarkGlucoseColors] from :shared) the phone's dark theme uses —
 * a "low" on the watch is the exact color of a "low" on the phone.
 */

// Brand tokens mirrored from :app's Color.kt (dark variants — watches are dark-only).
private val BrandTealDark = Color(0xFF4FD1C5)
private val OnBrandTeal = Color(0xFF003735)
private val TealContainer = Color(0xFF00504D)
private val OnTealContainer = Color(0xFF70F7EF)
private val Secondary = Color(0xFFB1CCC8)
private val OnSecondary = Color(0xFF1C3532)
private val SecondaryContainer = Color(0xFF334B48)
private val OnSecondaryContainer = Color(0xFFCDE8E3)

private val WearColorScheme = ColorScheme(
    primary = BrandTealDark,
    onPrimary = OnBrandTeal,
    primaryContainer = TealContainer,
    onPrimaryContainer = OnTealContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    background = Color.Black,
    onBackground = Color(0xFFDDE4E2),
    onSurface = Color(0xFFDDE4E2),
    onSurfaceVariant = Color(0xFFBEC9C7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Glucose-state colors for the watch (single dark set). */
val LocalGlucoseColors = staticCompositionLocalOf { DarkGlucoseColors }

object WearMoglucoTheme {
    val glucose: GlucoseColors
        @Composable get() = LocalGlucoseColors.current
}

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearColorScheme,
        content = content,
    )
}
