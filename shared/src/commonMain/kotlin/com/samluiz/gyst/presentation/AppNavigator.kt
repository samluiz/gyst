package com.samluiz.gyst.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MainTab {
    SUMMARY,
    EXPENSES,
    PLANNING,
    PROFILE,
}

sealed interface AppDestination {
    data class ImageImport(val sessionId: String? = null) : AppDestination

    data object DetectionSettings : AppDestination

    data object PendingSuggestions : AppDestination

    data class SuggestionReview(val suggestionId: String) : AppDestination
}

data class AppNavigationState(
    val selectedTab: MainTab = MainTab.SUMMARY,
    val destination: AppDestination? = null,
)

class AppNavigator {
    private val mutableState = MutableStateFlow(AppNavigationState())
    val state: StateFlow<AppNavigationState> = mutableState.asStateFlow()

    fun selectTab(tab: MainTab) {
        mutableState.value = AppNavigationState(selectedTab = tab)
    }

    fun navigate(destination: AppDestination) {
        mutableState.update { current -> current.copy(destination = destination) }
    }

    fun reviewSuggestion(suggestionId: String) {
        if (suggestionId.isBlank()) return
        mutableState.value =
            AppNavigationState(
                selectedTab = MainTab.PROFILE,
                destination = AppDestination.SuggestionReview(suggestionId),
            )
    }

    fun back(): Boolean {
        val current = mutableState.value
        if (current.destination == null) return false
        mutableState.value = current.copy(destination = null)
        return true
    }
}
