package org.nostr.nostrord.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrRepositoryApi

/**
 * Screen logic for the notifications list, shared by the Compose UI and the React web UI.
 * Exposes the notification history plus the metadata flows needed to render actors and
 * group context, and the read/clear actions. Compose obtains it via `viewModel { }`, web
 * via `useViewModel { }`.
 */
class NotificationsViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    private val store = AppModule.notificationHistoryStore

    val entries = store.entries
    val userMetadata = repo.userMetadata

    // Cross-relay lookups: notifications can come from background relays, so the screen
    // resolves group / relay names from these maps rather than the active-relay `groups`.
    val groupsByRelay = repo.groupsByRelay
    val relayMetadata = repo.relayMetadata

    fun markRead(id: String) {
        store.markRead(id)
    }

    fun markAllRead() {
        store.markAllRead()
    }

    fun clearHistory() {
        store.clearHistory()
    }

    /** Fetch display metadata for the given actor pubkeys (notification authors). */
    fun requestUserMetadata(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        viewModelScope.launch { repo.requestUserMetadata(pubkeys) }
    }
}
