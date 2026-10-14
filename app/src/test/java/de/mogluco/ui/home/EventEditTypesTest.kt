package de.mogluco.ui.home

import de.mogluco.data.EventCategory
import de.mogluco.data.EventType
import de.mogluco.data.LoggedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EventEditTypesTest {

    private val blood = EventType(6, "Blood glucose", EventCategory.BLOOD)
    private val activity = EventType(4, "Activity", EventCategory.ACTIVITY)
    private val carbs = EventType(1, "Carbohydrates", EventCategory.CARBS)
    private val dextrose = EventType(2, "Dextrose", EventCategory.CARBS)

    private fun event(type: EventType, index: Int = type.index) =
        LoggedEvent(epochMillis = 1_000L, type = index, value = 15f, category = type.category, label = type.name)

    @Test
    fun enabledKindIsEditedAsTheMenuEntry() {
        val types = listOf(blood, carbs, dextrose)
        val own = ownEventType(types, event(dextrose))
        assertSame(dextrose, own)
        assertSame(types, editChipTypes(types, own))
    }

    @Test
    fun disabledKindKeepsItsOwnTypeInsteadOfTheFirstEnabledOne() {
        // "Dextrose" turned off in Settings → Event logging: it used to open as Blood glucose.
        val types = listOf(blood, carbs)
        val own = ownEventType(types, event(dextrose))
        assertEquals(dextrose, own)
        assertEquals(listOf(blood, carbs, dextrose), editChipTypes(types, own))
    }

    @Test
    fun sameNameUnderAnotherIndexReplacesTheMenuEntry() {
        // A stored "Walk" (index 5) reads as "Activity", like the menu's entry for index 4.
        val types = listOf(blood, activity, carbs)
        val own = ownEventType(types, event(activity, index = 5))
        assertEquals(5, own.index)
        assertEquals(listOf(blood, own, carbs), editChipTypes(types, own))
    }

    @Test
    fun unlabelledEventStillGetsANamedChip() {
        val own = ownEventType(listOf(blood), LoggedEvent(epochMillis = 1_000L, type = 9, value = 1f))
        assertEquals(EventType(9, "Event", EventCategory.OTHER), own)
    }

    @Test
    fun newEventOffersTheMenuAsIs() {
        val types = listOf(blood, carbs)
        assertSame(types, editChipTypes(types, null))
    }
}
