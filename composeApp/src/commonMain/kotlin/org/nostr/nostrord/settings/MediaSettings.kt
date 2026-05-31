package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.storage.SecureStorage

/**
 * User-facing media preferences — toggled from Settings → Media.
 *
 * [autoLoadMedia] gates network fetches for inline chat media (images, videos,
 * YouTube thumbnails). When off, each media renders a "Tap to load" placeholder
 * and nothing is requested until the user opts in for that item. Default on, so
 * existing behavior is unchanged unless the user turns it down to save data.
 */
class MediaSettings {
    private val _autoLoadMedia =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_AUTO_LOAD_MEDIA, default = true),
        )

    val autoLoadMedia: StateFlow<Boolean> = _autoLoadMedia.asStateFlow()

    fun setAutoLoadMedia(enabled: Boolean) {
        _autoLoadMedia.value = enabled
        SecureStorage.saveBooleanPref(KEY_AUTO_LOAD_MEDIA, enabled)
    }

    private companion object {
        const val KEY_AUTO_LOAD_MEDIA = "media_auto_load"
    }
}
