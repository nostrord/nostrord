package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.storage.SecureStorage

/**
 * App theme preference, picked from Settings → Appearance.
 */
enum class AppTheme {
    DARK,
    LIGHT,
    SYSTEM,
    ;

    companion object {
        // Unknown / empty stored values fall back to SYSTEM: first launch follows
        // the OS theme until the user picks one in Settings → Appearance.
        fun fromStored(value: String): AppTheme = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * User-facing appearance preferences — set from Settings → Appearance.
 *
 * Only the preference lives here; both UIs read [theme] and resolve it to a
 * palette via `paletteForTheme` (ui/theme/ColorTokens.kt).
 */
class AppearanceSettings {
    private val _theme =
        MutableStateFlow(
            AppTheme.fromStored(SecureStorage.getStringPref(KEY_THEME, "")),
        )

    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    fun setTheme(theme: AppTheme) {
        _theme.value = theme
        SecureStorage.saveStringPref(KEY_THEME, theme.name)
    }

    private companion object {
        const val KEY_THEME = "appearance_theme"
    }
}
