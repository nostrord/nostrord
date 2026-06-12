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
        // Unknown / empty stored values fall back to DARK, the app's default palette.
        fun fromStored(value: String): AppTheme = entries.firstOrNull { it.name == value } ?: DARK
    }
}

/**
 * User-facing appearance preferences — set from Settings → Appearance.
 *
 * Only the preference lives here; both UIs read [theme] to resolve which
 * palette to render. DARK is the only palette implemented today, so LIGHT and
 * SYSTEM persist the user's choice without changing the rendered colors yet.
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
