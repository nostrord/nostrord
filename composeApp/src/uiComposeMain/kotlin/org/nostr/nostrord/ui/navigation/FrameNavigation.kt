package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * New-design frame navigation, provided by AppFrame to everything it renders so
 * deep components (e.g. the user profile modal inside the legacy GroupScreen) can
 * navigate to frame pages. Null outside the new-design frame (legacy navigation),
 * so consumers hide their navigation affordances there.
 */
val LocalFrameNavigator = staticCompositionLocalOf<((HashRoute) -> Unit)?> { null }
