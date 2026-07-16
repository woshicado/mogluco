package de.mogluco.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Mogluco brand palette (phone M3 scheme).
 *
 * Material 3's generic scheme is intentionally overridden with a calm, clinical
 * teal/indigo brand. The glucose-state tokens ([GlucoseColors], `colorFor`, the
 * Light/Dark instances) live in :shared so the watch uses the identical semantics.
 */

// --- Brand (seed) ---
internal val BrandTeal = Color(0xFF0E7C7B)
internal val BrandTealDark = Color(0xFF4FD1C5)
internal val BrandIndigo = Color(0xFF3D5AFE)

// --- Light scheme ---
internal val LightPrimary = BrandTeal
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFB8E6E4)
internal val LightOnPrimaryContainer = Color(0xFF00201F)
internal val LightSecondary = Color(0xFF4A6360)
internal val LightOnSecondary = Color(0xFFFFFFFF)
// Selection surfaces (chips, segmented buttons, nav indicator) — teal-family, not M3's
// baseline purple, which the defaults would otherwise inject next to the brand teal.
internal val LightSecondaryContainer = Color(0xFFCCE8E3)
internal val LightOnSecondaryContainer = Color(0xFF051F1D)
// Tertiary is the indigo half of the brand (see BrandIndigo) — used sparingly (warm-up banner).
internal val LightTertiary = BrandIndigo
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFDDE1FF)
internal val LightOnTertiaryContainer = Color(0xFF00105C)
internal val LightBackground = Color(0xFFF7FAF9)
internal val LightOnBackground = Color(0xFF191C1C)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightOnSurface = Color(0xFF191C1C)
internal val LightSurfaceVariant = Color(0xFFDAE5E3)
internal val LightOnSurfaceVariant = Color(0xFF3F4948)
// Surface-container ladder: teal-tinted neutrals between surface and background, so the
// NavigationBar (surfaceContainer) and menus/sheets stop rendering baseline purple-grey.
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF1F5F4)
internal val LightSurfaceContainer = Color(0xFFEBF0EF)
internal val LightSurfaceContainerHigh = Color(0xFFE5EAE9)
internal val LightSurfaceContainerHighest = Color(0xFFDFE4E3)
internal val LightOutline = Color(0xFF6F7977)
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)

// --- Dark scheme ---
internal val DarkPrimary = BrandTealDark
internal val DarkOnPrimary = Color(0xFF003735)
internal val DarkPrimaryContainer = Color(0xFF00504D)
internal val DarkOnPrimaryContainer = Color(0xFF70F7EF)
internal val DarkSecondary = Color(0xFFB1CCC8)
internal val DarkOnSecondary = Color(0xFF1C3532)
internal val DarkSecondaryContainer = Color(0xFF334B48)
internal val DarkOnSecondaryContainer = Color(0xFFCDE8E3)
internal val DarkTertiary = Color(0xFFBAC3FF)
internal val DarkOnTertiary = Color(0xFF08218A)
internal val DarkTertiaryContainer = Color(0xFF2A3DA8)
internal val DarkOnTertiaryContainer = Color(0xFFDDE1FF)
internal val DarkBackground = Color(0xFF0E1514)
internal val DarkOnBackground = Color(0xFFDDE4E2)
internal val DarkSurface = Color(0xFF111817)
internal val DarkOnSurface = Color(0xFFDDE4E2)
internal val DarkSurfaceVariant = Color(0xFF3F4948)
internal val DarkOnSurfaceVariant = Color(0xFFBEC9C7)
internal val DarkSurfaceContainerLowest = Color(0xFF090F0E)
internal val DarkSurfaceContainerLow = Color(0xFF171E1D)
internal val DarkSurfaceContainer = Color(0xFF1B2221)
internal val DarkSurfaceContainerHigh = Color(0xFF252C2B)
internal val DarkSurfaceContainerHighest = Color(0xFF303736)
internal val DarkOutline = Color(0xFF899391)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)
