package org.nostr.nostrord.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceSettingsTest {
    @Test
    fun fromStoredParsesKnownValuesAndDefaultsToSystem() {
        assertEquals(AppTheme.DARK, AppTheme.fromStored("DARK"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromStored("LIGHT"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStored("SYSTEM"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStored(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStored("garbage"))
    }

    @Test
    fun setThemePersistsAcrossInstances() {
        val settings = AppearanceSettings()
        val original = settings.theme.value
        try {
            settings.setTheme(AppTheme.LIGHT)
            assertEquals(AppTheme.LIGHT, settings.theme.value)
            assertEquals(AppTheme.LIGHT, AppearanceSettings().theme.value)
        } finally {
            settings.setTheme(original)
        }
    }
}
