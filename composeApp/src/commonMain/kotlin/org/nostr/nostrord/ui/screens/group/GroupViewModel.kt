package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.Result

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

    private val _deleteMessageError = MutableStateFlow<String?>(null)
    val deleteMessageError: StateFlow<String?> = _deleteMessageError

    private val _reactionError = MutableStateFlow<String?>(null)
    val reactionError: StateFlow<String?> = _reactionError

    fun clearDeleteMessageError() { _deleteMessageError.value = null }
    fun clearReactionError() { _reactionError.value = null }

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

    fun sendReaction(targetEventId: String, targetPubkey: String, emoji: String) {
        viewModelScope.launch {
            when (val result = repo.sendReaction(groupId, targetEventId, targetPubkey, emoji)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    val friendly = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
                    _reactionError.value = friendly
                }
                is Result.Success -> Unit
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            when (val result = repo.deleteMessage(groupId, messageId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    val friendly = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
                    _deleteMessageError.value = friendly
                }
                is Result.Success -> Unit
            }
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
