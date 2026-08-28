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

// ---------------- Stock label taxonomy ----------------

// Slots in the engine's stock label vocabulary. Settings::mklabels seeds the settings store with
// these seven labels in this fixed order on first run, taking the wording from the device
// language — the order is the same in every language, only the words differ.
private const val SLOT_DEXTROSE = 2
private const val SLOT_SLOW_INSULIN = 3

/** Category of each stock slot, indexed by slot number. */
private val SLOT_CATEGORY = listOf(
    EventCategory.INSULIN,  // 0 fast insulin
    EventCategory.CARBS,    // 1 carbohydrates
    EventCategory.CARBS,    // 2 dextrose
    EventCategory.INSULIN,  // 3 slow insulin
    EventCategory.ACTIVITY, // 4 bike
    EventCategory.ACTIVITY, // 5 walk
    EventCategory.BLOOD,    // 6 blood
)

/**
 * Every shipped language's stock labels, in slot order, copied verbatim from the engine's language
 * tables (Common/src/main/cpp/curve/<lang>jugglucotext.cpp).
 *
 * This table is what makes classification locale-proof, and it is not optional: a German install
 * stores "Ins schnell" / "Langes Insu" / "radeln" / "Blut", none of which contain an English
 * keyword, so recognising labels by English substring alone left insulin, cycling and blood
 * unclassified — which dropped them from the add-event menu entirely and made already-logged
 * entries of those kinds invisible on the graph. Keep the rows in sync when upstream adds or
 * retitles a language.
 */
private val STOCK_LABEL_ROWS = listOf(
    listOf("Fast Insuli", "Carbohydra", "Dextro", "Long Insuli", "Bike", "Walk", "Blood"),              // en
    listOf("Ins schnell", "Kohlenhydra", "Dextrose", "Langes Insu", "radeln", "Walk", "Blut"),          // de
    listOf("Insuli snel", "Koolhydraat", "Dextro", "Insuli lang", "Fietsen", "Wandelen", "Bloed"),      // nl
    listOf("Ins. rapide", "Glucides", "Dextro", "Ins. basale", "Vélo", "Marche", "Sang"),               // fr
    listOf("Fast Insuli", "Carbohidra", "Dextro", "Insu largos", "Bicicleta", "Caminar", "Sangre"),     // es
    listOf("Insu Rápida", "Carbohidra", "Glicemia", "Insul Lenta", "Bicicleta", "Caminhada", "Sangue"), // pt
    listOf("Rapida", "Carboidrati", "Glucosio", "Lenta", "Bike", "Walk", "Capillare"),                  // it
    listOf("Insul szybk", "Węglowodan", "Dextro", "Insul dług", "Rower", "Spacer", "Krew"),             // pl
    listOf("Insulin", "Kolhydrater", "Dextro", "Basinsulin", "Cykling", "Promenad", "Blod"),            // sv
    listOf("HizliEtkili", "Karbonhidrt", "Dextro", "Uzun Etkili", "Bisiklet", "Yürüyüş", "Kan Degeri"), // tr
    listOf("Kopoтkuй", "Yглeвoд", "Сахар", "Дoлгuй", "Велик", "Хoдьбa", "Кровь"),                       // ru
    listOf("Хуткі", "Вугляв", "Дэкстр", "Доўгі", "Ровар", "Прагул", "Кроў"),                            // be
    listOf("Швидкий", "Вуглев", "Дэкстр", "Довгий", "Велос", "Прагул", "Кров"),                         // uk
    listOf("מהיר Insuli", "קרבוהידרה", "דקסטרו", "לונג אינסולי", "אופניים", "ללכת", "דם"),              // iw
    listOf("快速胰岛素", "碳水化合物", "右旋糖酐", "长效胰岛素", "骑自行车", "步行", "血液"),                  // zh
)

/**
 * Lowercased stock label → slot. A word that lands in different slots in different languages is
 * dropped rather than guessed at, so an ambiguous match falls through to the keyword hints.
 */
private val STOCK_SLOTS: Map<String, Int> = buildMap {
    val ambiguous = mutableSetOf<String>()
    STOCK_LABEL_ROWS.forEach { row ->
        row.forEachIndexed { slot, label ->
            val key = label.lowercase()
            val previous = put(key, slot)
            if (previous != null && previous != slot) ambiguous += key
        }
    }
    ambiguous.forEach { remove(it) }
}

/**
 * The stock slot a stored label came from, or null when it isn't one of the shipped defaults —
 * i.e. the user renamed it, and the keyword hints below have to decide.
 */
private fun stockSlot(rawName: String): Int? {
    val n = rawName.trim().lowercase()
    if (n.isEmpty()) return null
    STOCK_SLOTS[n]?.let { return it }
    // Labels live in a 12-byte field natively, so a longer stock name (notably the multi-byte
    // Chinese and Hebrew ones) can read back truncated. Accept a prefix, but only an unambiguous
    // one — "insul" alone is the head of both the Polish fast and slow labels.
    if (n.length < 4) return null
    return STOCK_SLOTS.entries.filter { it.key.startsWith(n) }.map { it.value }.distinct().singleOrNull()
}

// Fallbacks for labels the user renamed by hand, matched as substrings of the lowercased label.
// The stock table above already covers untouched installs, so these only need to catch the common
// ways people retitle a slot — usually the actual insulin brand.
private val INSULIN_HINTS = listOf(
    "insul", "insu", "ins.", "rapid", "bolus", "basal", "hizli", "szybk", "schnell",
    "aspart", "lispro", "glulis", "fiasp", "humalog", "novorapid", "novolog", "apidra",
    "levemir", "tresiba", "lantus", "toujeo", "abasaglar", "degludec", "glargin", "detemir",
    "actrapid", "humulin", "胰岛素", "אינסולי",
)
private val CARBS_HINTS = listOf(
    "carb", "kohlenhydr", "koolhydr", "kolhydr", "karbonhid", "carboidr", "carbohidr",
    "glucid", "węglowodan", "weglowodan", "углевод", "вуглев", "вугляв", "碳水",
    "dextro", "glucos", "glicem", "右旋糖",
)
private val ACTIVITY_HINTS = listOf(
    "bike", "walk", "run", "exerc", "radel", "fiets", "wandel", "vélo", "velo", "marche",
    "bicicl", "camin", "rower", "spacer", "cykl", "promenad", "bisiklet", "yürüy", "yuruy",
    "велик", "ровар", "велос", "прагул", "ходьб", "骑", "步行",
)
private val BLOOD_HINTS = listOf(
    "blood", "blut", "bloed", "blod", "sangre", "sangue", "sang", "krew", "kan ", "capillare",
    "кров", "кроў", "血",
)
private val SLOW_INSULIN_HINTS = listOf(
    "long", "slow", "basal", "basin", "depot", "lang", "lent", "larg", "dług", "dlug",
    "uzun", "довг", "доўг", "长效", "לונג",
    "levemir", "tresiba", "lantus", "toujeo", "abasaglar", "degludec", "glargin", "detemir",
    "insulatard", "protaphan",
)
private val DEXTROSE_HINTS = listOf(
    "dextro", "glucos", "glicem", "дэкстр", "декстр", "сахар", "右旋糖",
)

/**
 * Whether an insulin label means the long-acting/basal insulin rather than the rapid bolus. Stock
 * labels resolve by slot, so this is language-independent. Shared by the display name and the
 * curated menu so the two can never disagree about which label is which.
 */
fun isSlowInsulinLabel(rawName: String): Boolean {
    stockSlot(rawName)?.let { return it == SLOT_SLOW_INSULIN }
    val n = rawName.lowercase()
    return SLOW_INSULIN_HINTS.any { it in n }
}

/** Whether a carbohydrate label means fast sugar (dextrose) rather than a regular meal. */
fun isDextroseLabel(rawName: String): Boolean {
    stockSlot(rawName)?.let { return it == SLOT_DEXTROSE }
    val n = rawName.lowercase()
    return DEXTROSE_HINTS.any { it in n }
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
            // An untouched stock label is decided by its slot, in any language; only renamed
            // labels fall through to the keyword hints.
            stockSlot(name)?.let { return SLOT_CATEGORY[it] }
            val n = name.lowercase().trim()
            return when {
                INSULIN_HINTS.any { it in n } -> EventCategory.INSULIN
                index == carbsIndex || CARBS_HINTS.any { it in n } -> EventCategory.CARBS
                ACTIVITY_HINTS.any { it in n } -> EventCategory.ACTIVITY
                // The stock "Blood" label is a blood-glucose reference. Without this, a blood event
                // logged at the label index (vs. the getbloodvar() calibration sentinel) read back
                // as OTHER → violet marker + wrong icon. Classify it so it's red everywhere.
                BLOOD_HINTS.any { it in n } -> EventCategory.BLOOD
                else -> EventCategory.OTHER
            }
        }
    }
}

/**
 * Canonical display name for an event, independent of the engine's stock label text. The native
 * labels are terse/inconsistent ("Long Insuli", "Dextro", separate "Bike"/"Walk") and localised to
 * whatever language the install was created in; we present a curated, fixed vocabulary instead.
 * Used by both the curated FAB list and the read-back mapping so a logged event reads the same name
 * everywhere. The underlying native index is never changed.
 */
fun eventDisplayName(rawName: String, category: EventCategory): String =
    when {
        category == EventCategory.BLOOD -> "Blood glucose"
        category == EventCategory.ACTIVITY -> "Activity"
        category == EventCategory.INSULIN && isSlowInsulinLabel(rawName) -> "Slow insulin"
        category == EventCategory.INSULIN -> "Fast insulin"
        category == EventCategory.CARBS && isDextroseLabel(rawName) -> "Dextrose"
        category == EventCategory.CARBS -> "Carbohydrates"
        else -> rawName
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

    // getbloodvar() reports the label count when no calibration label is configured, so bloodIndex
    // can point one past the taxonomy; fall back to whichever label classified as blood.
    val blood = raw.firstOrNull { it.index == bloodIndex }
        ?: raw.firstOrNull { it.category == EventCategory.BLOOD }
    add(blood, LogKind.BLOOD)

    add(raw.firstOrNull { it.category == EventCategory.ACTIVITY }, LogKind.ACTIVITY)

    add(raw.firstOrNull { it.category == EventCategory.INSULIN && isSlowInsulinLabel(it.name) }, LogKind.SLOW_INSULIN)
    add(raw.firstOrNull { it.category == EventCategory.INSULIN && !isSlowInsulinLabel(it.name) }, LogKind.FAST_INSULIN)
    add(raw.firstOrNull { it.category == EventCategory.CARBS && !isDextroseLabel(it.name) }, LogKind.CARBS)
    add(raw.firstOrNull { it.category == EventCategory.CARBS && isDextroseLabel(it.name) }, LogKind.DEXTROSE)

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
