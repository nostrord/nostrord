package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.utils.Result

/** A group offered in the `%group` mention autocomplete (with its hosting relay). */
data class MentionableGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
)

class GroupViewModel(
    private val repo: NostrRepositoryApi,
    val groupId: String,
) : ViewModel() {
    val messages = repo.messages
    val messageStatus = repo.messageStatus
    val connectionState = repo.connectionState
    val joinedGroups = repo.joinedGroups
    val joinedGroupsByRelay = repo.joinedGroupsByRelay
    val groups = repo.groups
    val groupsByRelay = repo.groupsByRelay
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
    val relayMetadata = repo.relayMetadata
    val childrenByParent = repo.childrenByParent
    val groupStates = repo.groupStates
    val zaps = repo.zaps
    val cachedEvents = repo.cachedEvents

    /**
     * Groups offered in the `%group` mention autocomplete: only the ones you're in plus the
     * ones discovered through people you follow (their kind:10009 lists, and friends present
     * in a group's member list) - not every group the relay ever served. Matches the
     * friend-based discovery used on the Home page.
     */
    @Suppress("UNCHECKED_CAST")
    val mentionableGroups: StateFlow<List<MentionableGroup>> =
        combine(
            listOf(
                repo.groupsByRelay,
                repo.joinedGroupsByRelay,
                repo.following,
                repo.userGroupLists,
                repo.groupMembers,
            ),
        ) { arr ->
            val byRelay = arr[0] as Map<String, List<GroupMetadata>>
            val joinedByRelay = arr[1] as Map<String, Set<String>>
            val following = arr[2] as Set<String>
            val lists = arr[3] as Map<String, List<UserGroupRef>>
            val members = arr[4] as Map<String, List<String>>

            val wanted = HashSet<String>()
            wanted.addAll(joinedByRelay.values.flatten())
            following.forEach { f -> lists[f].orEmpty().forEach { wanted.add(it.groupId) } }
            members.forEach { (gid, pks) -> if (pks.any { it in following }) wanted.add(gid) }

            byRelay
                .flatMap { (relay, list) -> list.filter { it.id in wanted }.map { MentionableGroup(relay, it) } }
                .distinctBy { it.meta.id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private val _deleteMessageError = MutableStateFlow<String?>(null)
    val deleteMessageError: StateFlow<String?> = _deleteMessageError

    private val _reactionError = MutableStateFlow<String?>(null)
    val reactionError: StateFlow<String?> = _reactionError

    fun clearSendError() {
        _sendError.value = null
    }

    fun clearDeleteMessageError() {
        _deleteMessageError.value = null
    }

    fun clearReactionError() {
        _reactionError.value = null
    }

    fun getPublicKey() = repo.getPublicKey()

    fun requestGroupMessages(channel: String?) {
        viewModelScope.launch { repo.requestGroupMessages(groupId, channel) }
    }

    fun sendMessage(
        content: String,
        channel: String?,
        mentions: Map<String, String>,
        replyToId: String?,
        extraTags: List<List<String>> = emptyList(),
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        _isSending.value = true
        _sendError.value = null
        viewModelScope.launch {
            // Optimistic send: the message is placed on screen with a Sending status
            // and delivered in the background, so the result here only signals whether
            // the local build/sign step succeeded. Transient relay timeouts and network
            // drops no longer surface as a toast; they resolve via the on-message status
            // (clock -> delivered, or a Failed indicator with retry). Only a real
            // build/sign failure (no optimistic message exists) restores the draft.
            when (repo.sendMessage(groupId, content, channel, mentions, replyToId, extraTags)) {
                is Result.Error -> {
                    _sendError.value = "Could not send message. Please try again."
                    onFailure()
                }
                is Result.Success -> onSuccess()
            }
            _isSending.value = false
        }
    }

    fun retrySend(eventId: String) = repo.retrySend(eventId)

    fun dismissFailed(eventId: String) = repo.dismissFailed(groupId, eventId)

    fun joinGroup(inviteCode: String? = null) {
        viewModelScope.launch { repo.joinGroup(groupId, inviteCode) }
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            repo.leaveGroup(groupId)
            onSuccess()
        }
    }

    fun deleteGroup(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(repo.deleteGroup(groupId))
        }
    }

    fun updateTopology(
        parent: org.nostr.nostrord.network.managers.GroupManager.ParentOp?,
        onDone: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            onDone(repo.updateGroupTopology(groupId, parent))
        }
    }

    // Reactions with an in-flight send, keyed "$targetEventId|$emoji". The reaction
    // only appears optimistically after signEvent resolves, which on NIP-46 (bunker)
    // is a 1-2s round-trip; we surface a pending badge + spinner during that window
    // and drop it once the real reaction lands (mirrors the web client).
    private val _pendingReactions = MutableStateFlow<Set<String>>(emptySet())
    val pendingReactions: StateFlow<Set<String>> = _pendingReactions

    fun sendReaction(
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
    ) {
        val key = "$targetEventId|$emoji"
        if (key in _pendingReactions.value) return
        _pendingReactions.value = _pendingReactions.value + key
        viewModelScope.launch {
            try {
                when (val result = repo.sendReaction(groupId, targetEventId, targetPubkey, emoji)) {
                    is Result.Error -> {
                        val raw = result.error.cause?.message ?: result.error.toString()
                        val friendly =
                            raw
                                .removePrefix("blocked: ")
                                .removePrefix("error: ")
                                .replaceFirstChar { it.uppercaseChar() }
                        _reactionError.value = friendly
                    }
                    is Result.Success -> Unit
                }
            } finally {
                _pendingReactions.value = _pendingReactions.value - key
            }
        }
    }

    private val _moderationError = MutableStateFlow<String?>(null)
    val moderationError: StateFlow<String?> = _moderationError

    fun clearModerationError() {
        _moderationError.value = null
    }

    fun addUser(
        targetPubkey: String,
        roles: List<String> = emptyList(),
        successMessage: String = "User added to the group",
    ) {
        viewModelScope.launch {
            when (val result = repo.addUser(groupId, targetPubkey, roles)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
                            .replaceFirstChar { it.uppercaseChar() }
                }
                // A kind:9000 (add / role change) is not reliably rendered in the
                // timeline, so confirm the action to the admin who triggered it.
                is Result.Success -> AppModule.postSystemMessage(successMessage)
            }
        }
    }

    fun removeUser(targetPubkey: String) {
        viewModelScope.launch {
            when (val result = repo.removeUser(groupId, targetPubkey)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
                            .replaceFirstChar { it.uppercaseChar() }
                }
                is Result.Success -> AppModule.postSystemMessage("User removed from the group")
            }
        }
    }

    fun promoteToAdmin(targetPubkey: String) {
        addUser(targetPubkey, listOf("admin"), successMessage = "User promoted to admin")
    }

    fun demoteFromAdmin(targetPubkey: String) {
        // Re-add user without admin role to demote
        addUser(targetPubkey, emptyList(), successMessage = "Admin role removed")
    }

    fun approveJoinRequest(targetPubkey: String) {
        addUser(targetPubkey, successMessage = "Join request approved")
    }

    fun rejectJoinRequest(joinRequestEventId: String) {
        viewModelScope.launch {
            when (val result = repo.rejectJoinRequest(groupId, joinRequestEventId)) {
                is Result.Error -> {
                    val raw = result.error.cause?.message ?: result.error.toString()
                    _moderationError.value =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
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
                    val friendly =
                        when {
                            cleaned.contains("kind 9009 not allowed", ignoreCase = true) ||
                                cleaned.contains("not allowed", ignoreCase = true) &&
                                cleaned.contains("9009") ->
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
                    _moderationError.value =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
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
                    val friendly =
                        raw
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
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

    fun loadMoreMessages(channel: String? = null) {
        viewModelScope.launch { repo.loadMoreMessages(groupId, channel) }
    }

    fun fetchMessageById(messageId: String) {
        viewModelScope.launch { repo.fetchGroupMessageById(groupId, messageId) }
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

    fun markAsReadUpTo(timestamp: Long) {
        repo.markGroupAsReadUpTo(groupId, timestamp)
    }

    fun getLastReadTimestamp(): Long? = repo.getLastReadTimestamp(groupId)

    fun resetGroupLoadingState() {
        viewModelScope.launch { repo.resetGroupLoadingState(groupId) }
    }

    fun requestPendingJoinRequests() {
        viewModelScope.launch { repo.requestPendingJoinRequests(groupId) }
    }

    fun requestUserMetadata(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        viewModelScope.launch { repo.requestUserMetadata(pubkeys) }
    }

    fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
    ) {
        viewModelScope.launch { repo.requestEventById(eventId, relayHints, author) }
    }

    /** Preview a referenced group (the [previewGroupId] may differ from this VM's group). */
    fun fetchGroupPreview(
        previewGroupId: String,
        relayUrl: String,
    ) {
        viewModelScope.launch { repo.fetchGroupPreview(previewGroupId, relayUrl) }
    }
}
