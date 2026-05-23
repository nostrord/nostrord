package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadGroupNotificationLevelsFor
import org.nostr.nostrord.storage.saveGroupNotificationLevelsFor

/**
 * How noisy a group's notifications are. Applied per-group, falling back to the
 * global default for groups the user hasn't overridden.
 *
 * - [ALL] — every message notifies (current default).
 * - [MENTIONS_REPLIES] — only direct replies, @mentions and reactions to the
 *   user's own messages notify. Ordinary chatter is silent.
 * - [MUTED] — nothing notifies, not even direct mentions/replies.
 *
 * Unread badges are independent of this setting: a quiet or muted group still
 * accumulates its unread count, it just doesn't fire the feed/sound/popup.
 */
enum class NotificationLevel { ALL, MENTIONS_REPLIES, MUTED }

/**
 * User-facing notification preferences — toggled from Settings → Notifications.
 *
 * Sound applies to every platform that has a NotificationSound actual (web/desktop/android).
 * System popups are gated on the per-platform [NotificationService.isSupported] check
 * inside the consumer; this flag just lets the user opt out even when supported.
 *
 * Per-group [NotificationLevel] overrides are account-scoped and reloaded on
 * account switch via [initialize] / [clear].
 */
class NotificationSettings {
    private val _soundEnabled =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_SOUND_ENABLED, default = true),
        )
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _systemNotificationsEnabled =
        MutableStateFlow(
            SecureStorage.getBooleanPref(KEY_SYSTEM_ENABLED, default = true),
        )
    val systemNotificationsEnabled: StateFlow<Boolean> = _systemNotificationsEnabled.asStateFlow()

    // Global default applied to groups the user hasn't explicitly overridden.
    private val _defaultLevel =
        MutableStateFlow(
            runCatching {
                NotificationLevel.valueOf(
                    SecureStorage.getStringPref(KEY_DEFAULT_LEVEL, NotificationLevel.ALL.name),
                )
            }.getOrDefault(NotificationLevel.ALL),
        )
    val defaultLevel: StateFlow<NotificationLevel> = _defaultLevel.asStateFlow()

    // Per-account, per-group overrides. Reloaded on account switch.
    private val _groupLevels = MutableStateFlow<Map<String, NotificationLevel>>(emptyMap())
    val groupLevels: StateFlow<Map<String, NotificationLevel>> = _groupLevels.asStateFlow()

    private var currentPubkey: String? = null

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SOUND_ENABLED, enabled)
    }

    fun setSystemNotificationsEnabled(enabled: Boolean) {
        _systemNotificationsEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SYSTEM_ENABLED, enabled)
    }

    fun setDefaultLevel(level: NotificationLevel) {
        _defaultLevel.value = level
        SecureStorage.saveStringPref(KEY_DEFAULT_LEVEL, level.name)
    }

    /** Load the active account's per-group overrides. Call on account activation. */
    fun initialize(pubkey: String) {
        currentPubkey = pubkey
        _groupLevels.value =
            SecureStorage.loadGroupNotificationLevelsFor(pubkey)
                .mapNotNull { (id, name) ->
                    runCatching { id to NotificationLevel.valueOf(name) }.getOrNull()
                }
                .toMap()
    }

    /** Drop the active account's overrides on logout / account switch. */
    fun clear() {
        currentPubkey = null
        _groupLevels.value = emptyMap()
    }

    fun setGroupLevel(
        groupId: String,
        level: NotificationLevel,
    ) {
        val pubkey = currentPubkey ?: return
        _groupLevels.update { it + (groupId to level) }
        SecureStorage.saveGroupNotificationLevelsFor(
            pubkey,
            _groupLevels.value.mapValues { it.value.name },
        )
    }

    fun effectiveLevelFor(groupId: String): NotificationLevel = _groupLevels.value[groupId] ?: _defaultLevel.value

    /**
     * Whether a notification should fire for a message of the given [level].
     * [isDirect] is true for replies, @mentions and reactions to the user's own
     * message; false for ordinary group chatter.
     */
    fun shouldNotify(
        level: NotificationLevel,
        isDirect: Boolean,
    ): Boolean = when (level) {
        NotificationLevel.ALL -> true
        NotificationLevel.MENTIONS_REPLIES -> isDirect
        NotificationLevel.MUTED -> false
    }

    private companion object {
        const val KEY_SOUND_ENABLED = "notif_sound_enabled"
        const val KEY_SYSTEM_ENABLED = "notif_system_enabled"
        const val KEY_DEFAULT_LEVEL = "notif_default_level"
    }
}
