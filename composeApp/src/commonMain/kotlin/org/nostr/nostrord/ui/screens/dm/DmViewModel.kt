package org.nostr.nostrord.ui.screens.dm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

/**
 * Shared DM screen logic (commonMain): the conversation list, per-peer message threads, and
 * sending. Both the web DM screens and the native ones consume this same VM so behavior stays in
 * one place. NIP-17 send/receive lives in the repository (DmManager); this is a thin layer over it.
 */
class DmViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val conversations = repo.dmConversations
    val messagesByPeer = repo.dmMessagesByPeer
    val userMetadata = repo.userMetadata

    fun getPublicKey(): String? = repo.getPublicKey()

    fun send(recipientPubkey: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { repo.sendDm(recipientPubkey, text) }
    }
}
