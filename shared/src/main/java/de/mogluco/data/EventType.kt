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
package de.mogluco.data

/** Coarse category for an engine event label, used to pick unit + icon + graph marker. */
enum class EventCategory { CARBS, INSULIN, ACTIVITY, BLOOD, OTHER }

/**
 * A user-toggleable logging option — finer than [EventCategory] so carbs vs dextrose and slow vs
 * fast insulin can be enabled independently. Order matches the add/edit speed-dial.
 */
enum class LogKind(val category: EventCategory, val label: String) {
    BLOOD(EventCategory.BLOOD, "Blood glucose"),
    ACTIVITY(EventCategory.ACTIVITY, "Activity"),
    SLOW_INSULIN(EventCategory.INSULIN, "Slow insulin"),
    FAST_INSULIN(EventCategory.INSULIN, "Fast insulin"),
    CARBS(EventCategory.CARBS, "Carbohydrates"),
    DEXTROSE(EventCategory.CARBS, "Dextrose"),
}

/** Display unit suffix for a category's value (g / U / blank). Single source of truth. */
val EventCategory.unit: String
    get() = when (this) {
        EventCategory.CARBS -> "g"
        EventCategory.INSULIN -> "U"
        else -> ""
    }

/** An engine event type, derived from the native `getLabels()` taxonomy. */
data class EventType(
    val index: Int,
    val name: String,
    val category: EventCategory,
) {
    /** Display unit for the value field. */
    val unit: String get() = category.unit

    companion object {
        fun classify(index: Int, name: String, carbsIndex: Int): EventCategory {
            val n = name.lowercase().trim()
            return when {
                "insul" in n -> EventCategory.INSULIN
                index == carbsIndex || "carb" in n || "dextro" in n -> EventCategory.CARBS
                "bike" in n || "walk" in n || "run" in n || "exerc" in n -> EventCategory.ACTIVITY
                // The stock "Blood" label is a blood-glucose reference. Without this, a blood event
                // logged at the label index (vs. the getbloodvar() calibration sentinel) read back
                // as OTHER → violet marker + wrong icon. Classify it so it's red everywhere.
                "blood" in n -> EventCategory.BLOOD
                else -> EventCategory.OTHER
            }
        }
    }
}

/**
 * Canonical display name for an event, independent of the engine's stock label text. The native
 * labels are terse/inconsistent ("Long Insuli", "Dextro", separate "Bike"/"Walk"); we present a
 * curated, fixed vocabulary instead. Used by both the curated FAB list and the read-back mapping
 * so a logged event reads the same name everywhere. The underlying native index is never changed.
 */
fun eventDisplayName(rawName: String, category: EventCategory): String {
    val n = rawName.lowercase()
    return when {
        category == EventCategory.BLOOD -> "Blood glucose"
        category == EventCategory.ACTIVITY -> "Activity"
        category == EventCategory.INSULIN &&
            ("long" in n || "slow" in n || "basal" in n || "depot" in n) -> "Slow insulin"
        category == EventCategory.INSULIN -> "Fast insulin"
        category == EventCategory.CARBS && "dextro" in n -> "Dextrose"
        category == EventCategory.CARBS -> "Carbohydrates"
        else -> rawName
    }
}

/**
 * The curated, ordered event vocabulary shown in the add/edit UI, mapped onto the engine's stock
 * labels. Order (top→bottom in the speed-dial): Blood glucose, Activity, Slow insulin, Fast
 * insulin, Carbohydrates, Dextrose. Bike+Walk collapse into a single "Activity" (logged to the
 * first activity index); display names come from [eventDisplayName]; native [EventType.index]
 * stays fixed so logging/read-back are unaffected. Only categories in [enabled] are offered.
 */
fun curatedDisplayTypes(raw: List<EventType>, bloodIndex: Int, enabled: Set<LogKind>): List<EventType> {
    val out = mutableListOf<EventType>()
    fun add(t: EventType?, kind: LogKind) {
        if (t != null && kind in enabled) out += t.copy(name = eventDisplayName(t.name, kind.category), category = kind.category)
    }

    val blood = raw.firstOrNull { it.index == bloodIndex } ?: raw.firstOrNull { "blood" in it.name.lowercase() }
    add(blood, LogKind.BLOOD)

    add(raw.firstOrNull { it.category == EventCategory.ACTIVITY }, LogKind.ACTIVITY)

    add(
        raw.firstOrNull {
            it.category == EventCategory.INSULIN &&
                it.name.lowercase().let { n -> "long" in n || "slow" in n || "basal" in n || "depot" in n }
        },
        LogKind.SLOW_INSULIN,
    )
    add(
        raw.firstOrNull {
            it.category == EventCategory.INSULIN &&
                it.name.lowercase().let { n -> !("long" in n || "slow" in n || "basal" in n || "depot" in n) }
        },
        LogKind.FAST_INSULIN,
    )
    add(raw.firstOrNull { it.category == EventCategory.CARBS && "dextro" !in it.name.lowercase() }, LogKind.CARBS)
    add(raw.firstOrNull { "dextro" in it.name.lowercase() }, LogKind.DEXTROSE)

    return out.distinctBy { it.index }
}

/** A logged event read back from the engine. */
data class LoggedEvent(
    val epochMillis: Long,
    val type: Int,
    val value: Float,
    val note: String = "",
    val category: EventCategory = EventCategory.OTHER,
    val label: String = "",
) {
    /** Stable-enough identity for edit/delete in the UI (engine has no row id yet). */
    val id: Long get() = epochMillis * 31 + type
}
