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
    val groupRoles = repo.groupRoles
    val loadingMembers = repo.loadingMembers
    val restrictedGroups = repo.restrictedGroups
    val isLoadingMore = repo.isLoadingMore
    val hasMoreMessages = repo.hasMoreMessages
    val currentRelayUrl = repo.currentRelayUrl
    val childrenByParent = repo.childrenByParent

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private val _deleteMessageError = MutableStateFlow<String?>(null)
    val deleteMessageError: StateFlow<String?> = _deleteMessageError

    private val _reactionError = MutableStateFlow<String?>(null)
    val reactionError: StateFlow<String?> = _reactionError

    fun clearSendError() { _sendError.value = null }
    fun clearDeleteMessageError() { _deleteMessageError.value = null }
    fun clearReactionError() { _reactionError.value = null }

    fun getPublicKey() = repo.getPublicKey()

    fun requestGroupMessages(channel: String?) {
        viewModelScope.launch { repo.requestGroupMessages(groupId, channel) }
    }

    fun sendMessage(content: String, channel: String?, mentions: Map<String, String>, replyToId: String?, extraTags: List<List<String>> = emptyList()) {
        _isSending.value = true
        _sendError.value = null
        viewModelScope.launch {
            when (val result = repo.sendMessage(groupId, content, channel, mentions, replyToId, extraTags)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    val cleaned = raw.removePrefix("blocked: ").removePrefix("error: ")
                    val friendly = when {
                        cleaned.contains("unknown member", ignoreCase = true) ->
                            "Your join request is pending admin approval. You cannot send messages until approved."
                        cleaned.contains("Channel was cancelled", ignoreCase = true) ||
                        cleaned.contains("not connected", ignoreCase = true) ||
                        cleaned.contains("Disconnected", ignoreCase = true) -> {
                            repo.triggerReconnect()
                            "Connection lost. Reconnecting..."
                        }
                        cleaned.contains("timed out", ignoreCase = true) ||
                        cleaned.contains("timeout", ignoreCase = true) ->
                            "Message send timed out. It will be retried automatically."
                        else -> cleaned.replaceFirstChar { it.uppercaseChar() }
                    }
                    _sendError.value = friendly
                }
                is Result.Success -> {}
            }
            _isSending.value = false
        }
    }

    fun joinGroup(inviteCode: String? = null) {
        viewModelScope.launch { repo.joinGroup(groupId, inviteCode) }
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            repo.leaveGroup(groupId)
            onSuccess()
        }
    }

    fun deleteGroup(
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(repo.deleteGroup(groupId))
        }
    }

    fun updateTopology(
        parent: org.nostr.nostrord.network.managers.GroupManager.ParentOp?,
        onDone: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onDone(repo.updateGroupTopology(groupId, parent))
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

    private val _moderationError = MutableStateFlow<String?>(null)
    val moderationError: StateFlow<String?> = _moderationError
    fun clearModerationError() { _moderationError.value = null }

    fun addUser(targetPubkey: String, roles: List<String> = emptyList()) {
        viewModelScope.launch {
            when (val result = repo.addUser(groupId, targetPubkey, roles)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
                }
                is Result.Success -> Unit
            }
        }
    }

    fun removeUser(targetPubkey: String) {
        viewModelScope.launch {
            when (val result = repo.removeUser(groupId, targetPubkey)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
                }
                is Result.Success -> Unit
            }
        }
    }

    fun promoteToAdmin(targetPubkey: String) {
        addUser(targetPubkey, listOf("admin"))
    }

    fun demoteFromAdmin(targetPubkey: String) {
        // Re-add user without admin role to demote
        addUser(targetPubkey, emptyList())
    }

    fun approveJoinRequest(targetPubkey: String) {
        addUser(targetPubkey)
    }

    fun rejectJoinRequest(joinRequestEventId: String) {
        viewModelScope.launch {
            when (val result = repo.rejectJoinRequest(groupId, joinRequestEventId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
                }
                is Result.Success -> Unit
            }
        }
    }

    fun createInviteCode(onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repo.createInviteCode(groupId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    val cleaned = raw.removePrefix("blocked: ").removePrefix("error: ")
                    val friendly = when {
                        cleaned.contains("kind 9009 not allowed", ignoreCase = true) ||
                        cleaned.contains("not allowed", ignoreCase = true) && cleaned.contains("9009") ->
                            "This relay does not support invite codes."
                        else -> cleaned.replaceFirstChar { it.uppercaseChar() }
                    }
                    _moderationError.value = friendly
                }
                is Result.Success -> onSuccess(result.data)
            }
        }
    }

    fun revokeInviteCode(eventId: String) {
        viewModelScope.launch {
            when (val result = repo.revokeInviteCode(groupId, eventId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value = raw.removePrefix("blocked: ").removePrefix("error: ")
                        .replaceFirstChar { it.uppercaseChar() }
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

    fun refreshGroupData() {
        viewModelScope.launch {
            repo.requestGroupMembers(groupId)
            repo.requestGroupAdmins(groupId)
            repo.requestGroupMessages(groupId)
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

    fun markAsRead() {
        repo.markGroupAsRead(groupId)
    }

    fun getLastReadTimestamp(): Long? = repo.getLastReadTimestamp(groupId)
}
