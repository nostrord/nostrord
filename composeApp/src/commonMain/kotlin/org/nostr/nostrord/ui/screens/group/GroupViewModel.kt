package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class GroupViewModel(
    private val repo: NostrRepositoryApi,
    val groupId: String
) : ViewModel() {

    val messages = repo.messages
    val connectionState = repo.connectionState
    val joinedGroups = repo.joinedGroups
    val groups = repo.groups
    val userMetadata = repo.userMetadata
    val reactions = repo.reactions
    val groupMembers = repo.groupMembers
    val groupAdmins = repo.groupAdmins
    val isLoadingMore = repo.isLoadingMore
    val hasMoreMessages = repo.hasMoreMessages

    fun getPublicKey() = repo.getPublicKey()

    fun requestGroupMessages(channel: String?) {
        viewModelScope.launch { repo.requestGroupMessages(groupId, channel) }
    }

    fun sendMessage(content: String, channel: String?, mentions: Map<String, String>, replyToId: String?) {
        viewModelScope.launch { repo.sendMessage(groupId, content, channel, mentions, replyToId) }
    }

    fun joinGroup() {
        viewModelScope.launch { repo.joinGroup(groupId) }
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            repo.leaveGroup(groupId)
            onSuccess()
        }
    }

    fun deleteGroup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            repo.deleteGroup(groupId)
            onSuccess()
        }
    }

    fun loadMoreMessages(channel: String?) {
        viewModelScope.launch { repo.loadMoreMessages(groupId, channel) }
    }

    fun switchRelay(relayUrl: String) {
        viewModelScope.launch { repo.switchRelay(relayUrl) }
    }

    fun reconnect() {
        viewModelScope.launch { repo.reconnect() }
    }
}
