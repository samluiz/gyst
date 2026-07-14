package com.samluiz.gyst.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test
    fun deepLinkSelectsProfileAndExactSuggestion() {
        val navigator = AppNavigator()

        navigator.reviewSuggestion("suggestion-42")

        assertEquals(MainTab.PROFILE, navigator.state.value.selectedTab)
        assertEquals(AppDestination.SuggestionReview("suggestion-42"), navigator.state.value.destination)
    }

    @Test
    fun selectingTabClearsDetailDestination() {
        val navigator = AppNavigator()
        navigator.navigate(AppDestination.DetectionSettings)

        navigator.selectTab(MainTab.EXPENSES)

        assertEquals(MainTab.EXPENSES, navigator.state.value.selectedTab)
        assertNull(navigator.state.value.destination)
    }

    @Test
    fun backConsumesOnlyDetailDestinations() {
        val navigator = AppNavigator()
        assertFalse(navigator.back())
        navigator.navigate(AppDestination.ImageImport("import-1"))

        assertTrue(navigator.back())
        assertNull(navigator.state.value.destination)
    }
}
