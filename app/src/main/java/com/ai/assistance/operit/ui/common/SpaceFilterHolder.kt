package com.ai.assistance.operit.ui.common

/**
 * Global state holder for space filter (work/personal).
 * When non-null, AIChatScreen will filter chat list by group.
 */
object SpaceFilterHolder {
    /** Current active space filter. null = show all chats */
    var spaceFilter: String? = null

    /** Callback to apply filter when navigating to a space */
    fun setFilter(group: String?) {
        spaceFilter = group
    }
}