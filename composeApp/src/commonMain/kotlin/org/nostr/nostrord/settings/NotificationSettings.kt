package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.storage.SecureStorage

/**
 * User-facing notification preferences — toggled from Settings → Notifications.
 *
 * Sound applies to every platform that has a NotificationSound actual (web/desktop/android).
 * System popups are gated on the per-platform [NotificationService.isSupported] check
 * inside the consumer; this flag just lets the user opt out even when supported.
 */
class NotificationSettings {
    private val _soundEnabled = MutableStateFlow(
        SecureStorage.getBooleanPref(KEY_SOUND_ENABLED, default = true)
    )
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _systemNotificationsEnabled = MutableStateFlow(
        SecureStorage.getBooleanPref(KEY_SYSTEM_ENABLED, default = true)
    )
    val systemNotificationsEnabled: StateFlow<Boolean> = _systemNotificationsEnabled.asStateFlow()

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SOUND_ENABLED, enabled)
    }

    fun setSystemNotificationsEnabled(enabled: Boolean) {
        _systemNotificationsEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SYSTEM_ENABLED, enabled)
    }

    private companion object {
        const val KEY_SOUND_ENABLED = "notif_sound_enabled"
        const val KEY_SYSTEM_ENABLED = "notif_system_enabled"
    }
}
