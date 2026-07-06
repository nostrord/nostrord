package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.storage.SecureStorage

/**
 * Master on/off switch for the entire direct-messages feature — toggled from
 * Settings → Direct Messages.
 *
 * When off, the app stops subscribing to the NIP-17 gift-wrap inbox (kind:1059)
 * and never unwraps or decrypts DMs, and all DM UI (rail entry, sidebar, unread
 * badges, "Message" buttons, DM relay editor) is hidden. Default on, so existing
 * behavior is unchanged unless the user opts out. Your published kind:10050 DM
 * relay list is left untouched — this switch is local only.
 */
class DmSettings {
    private val _dmEnabled =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_DM_ENABLED, default = true),
        )

    val dmEnabled: StateFlow<Boolean> = _dmEnabled.asStateFlow()

    fun setDmEnabled(enabled: Boolean) {
        _dmEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_DM_ENABLED, enabled)
    }

    private companion object {
        const val KEY_DM_ENABLED = "dm_enabled"
    }
}
