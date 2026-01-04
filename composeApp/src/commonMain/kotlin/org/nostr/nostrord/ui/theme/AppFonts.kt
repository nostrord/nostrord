package org.nostr.nostrord.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Application font configuration for Compose Web Canvas rendering.
 *
 * CRITICAL: Compose Web uses Skia Canvas which does NOT access browser fonts.
 * FontFamily.SansSerif only maps to Skia's bundled Latin-only font.
 * We must explicitly set a FontFamily with all required fonts.
 *
 * On web: Call `AppFonts.setDefaultFontFamily()` after fonts are loaded.
 * On other platforms: System fonts work automatically via FontFamily.SansSerif.
 */
object AppFonts {
    /**
     * Default font family used by Typography.
     *
     * On web, this is set to a custom FontFamily containing all loaded fonts.
     * On other platforms, this remains FontFamily.SansSerif (system fonts).
     */
    var defaultFontFamily: FontFamily = FontFamily.SansSerif
        private set

    /**
     * Monospace font family for code blocks.
     */
    var monospaceFontFamily: FontFamily = FontFamily.Monospace
        private set

    /**
     * Set the default font family (called from web after fonts load).
     * This updates all Typography styles to use the new font.
     */
    fun setDefaultFontFamily(fontFamily: FontFamily) {
        defaultFontFamily = fontFamily
    }

    /**
     * Set the monospace font family.
     */
    fun setMonospaceFontFamily(fontFamily: FontFamily) {
        monospaceFontFamily = fontFamily
    }
}
