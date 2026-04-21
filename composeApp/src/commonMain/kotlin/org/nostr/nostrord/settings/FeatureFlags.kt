package org.nostr.nostrord.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.storage.SecureStorage

/**
 * User-facing feature flags — toggled from Settings → Experimental.
 *
 * These gate UI for features whose protocol spec has not yet been accepted
 * upstream (currently just NIP-29 subgroups). The flag does not touch the
 * data layer: manifest/parent/child events keep flowing through the repo,
 * but UI entry points stay hidden until the user opts in.
 */
class FeatureFlags {
    private val _subgroupsEnabled = MutableStateFlow(
        SecureStorage.getBooleanPref(KEY_SUBGROUPS_ENABLED, default = false)
    )

    /** NIP-29 subgroups (draft) — parent/child hierarchy, create subgroup, manage children. */
    val subgroupsEnabled: StateFlow<Boolean> = _subgroupsEnabled.asStateFlow()

    fun setSubgroupsEnabled(enabled: Boolean) {
        _subgroupsEnabled.value = enabled
        SecureStorage.saveBooleanPref(KEY_SUBGROUPS_ENABLED, enabled)
    }

    private companion object {
        const val KEY_SUBGROUPS_ENABLED = "feature_subgroups_enabled"
    }
}
