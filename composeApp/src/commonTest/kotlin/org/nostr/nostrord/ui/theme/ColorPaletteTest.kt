package org.nostr.nostrord.ui.theme

import org.nostr.nostrord.settings.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorPaletteTest {
    @Test
    fun resolvesPreferenceToPalette() {
        assertEquals(DarkColorPalette, paletteForTheme(AppTheme.DARK, systemDark = false))
        assertEquals(LightColorPalette, paletteForTheme(AppTheme.LIGHT, systemDark = true))
        assertEquals(DarkColorPalette, paletteForTheme(AppTheme.SYSTEM, systemDark = true))
        assertEquals(LightColorPalette, paletteForTheme(AppTheme.SYSTEM, systemDark = false))
        assertTrue(DarkColorPalette.isDark)
        assertFalse(LightColorPalette.isDark)
    }

    @Test
    fun darkPaletteMirrorsColorTokens() {
        assertEquals(ColorTokens.Background, DarkColorPalette.background)
        assertEquals(ColorTokens.TextPrimary, DarkColorPalette.textPrimary)
        assertEquals(ColorTokens.Primary, DarkColorPalette.primary)
        assertEquals(ColorTokens.Divider, DarkColorPalette.divider)
    }
}
