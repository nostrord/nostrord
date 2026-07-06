package org.nostr.nostrord.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmSettingsTest {
    @Test
    fun defaultsToEnabled() {
        val settings = DmSettings()
        val original = settings.dmEnabled.value
        try {
            // A fresh account with nothing stored must have DMs on, so existing users are unaffected.
            settings.setDmEnabled(true)
            assertTrue(DmSettings().dmEnabled.value)
        } finally {
            settings.setDmEnabled(original)
        }
    }

    @Test
    fun setDmEnabledPersistsAcrossInstances() {
        val settings = DmSettings()
        val original = settings.dmEnabled.value
        try {
            settings.setDmEnabled(false)
            assertEquals(false, settings.dmEnabled.value)
            assertEquals(false, DmSettings().dmEnabled.value)

            settings.setDmEnabled(true)
            assertEquals(true, settings.dmEnabled.value)
            assertEquals(true, DmSettings().dmEnabled.value)
        } finally {
            settings.setDmEnabled(original)
        }
    }
}
