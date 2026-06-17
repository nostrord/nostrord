package org.nostr.nostrord.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.Result

/**
 * Shared logic for the Settings "Direct message relays" editor (NIP-17 kind:10050). Both the web
 * and Compose panels consume this VM so the add/remove/publish behavior lives in one place.
 *
 * [relays] is the published list (or the defaults until one is published). The editor keeps a
 * local draft and publishes it as a kind:10050 via the repository.
 */
class DmRelaySettingsViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val relays: StateFlow<List<String>> = repo.myDmRelays

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** Publish [draft] as our kind:10050 after normalizing wss:// URLs and dropping blanks/dupes. */
    fun publish(draft: List<String>) {
        val clean = draft.map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            _error.value = "Add at least one relay."
            return
        }
        _error.value = null
        _saving.value = true
        viewModelScope.launch {
            when (val result = repo.publishDmRelayList(clean)) {
                is Result.Error -> _error.value = result.error.message
                else -> {}
            }
            _saving.value = false
        }
    }

    /** Accepts "relay.example.com", "wss://relay.example.com" or trailing-slash forms. */
    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) trimmed else "wss://$trimmed"
    }
}
