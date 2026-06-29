package org.nostr.nostrord.ui.screens.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepositoryApi

/** One row in the threads list: a kind:11 root plus stats derived from its kind:1111 replies. */
data class ThreadSummary(
    val rootId: String,
    val authorPubkey: String,
    val title: String,
    val preview: String,
    val replyCount: Int,
    val lastActivity: Long,
    val createdAt: Long,
    val replierPubkeys: List<String>,
)

/** A single open thread: its kind:11 root plus the kind:1111 replies, oldest-first. */
data class ThreadDetail(
    val root: NostrGroupClient.NostrMessage,
    val replies: List<NostrGroupClient.NostrMessage>,
)

/** The `E` (root-scope) tag of a kind:1111 reply: the id of the thread it belongs to. */
internal fun NostrGroupClient.NostrMessage.threadRootIdTag(): String? = tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)

/** A thread's title: its NIP-14 `subject` tag, else the first non-blank line of the content. */
internal fun NostrGroupClient.NostrMessage.threadTitle(): String {
    val subject = tags.firstOrNull { it.size >= 2 && it[0] == "subject" }?.get(1)?.trim()
    if (!subject.isNullOrEmpty()) return subject
    return content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
        ?: "Untitled thread"
}

/**
 * Pure derivation of the threads list from the raw kind:11 roots and kind:1111 replies, kept out
 * of the VM's coroutine plumbing so it is unit-testable. Replies are matched to their root by the
 * uppercase `E` tag; the list is ordered by last activity (newest first).
 */
internal fun buildThreadSummaries(
    roots: List<NostrGroupClient.NostrMessage>,
    replies: List<NostrGroupClient.NostrMessage>,
): List<ThreadSummary> {
    val repliesByRoot = replies.groupBy { it.threadRootIdTag() }
    return roots
        .distinctBy { it.id }
        .map { root ->
            val rs = repliesByRoot[root.id] ?: emptyList()
            val preview = root.content.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() }.orEmpty().take(140)
            ThreadSummary(
                rootId = root.id,
                authorPubkey = root.pubkey,
                title = root.threadTitle(),
                preview = preview,
                replyCount = rs.size,
                lastActivity = rs.maxOfOrNull { it.createdAt } ?: root.createdAt,
                createdAt = root.createdAt,
                replierPubkeys = rs.map { it.pubkey }.distinct(),
            )
        }
        .sortedByDescending { it.lastActivity }
}

/**
 * Shared screen logic for the forum-style Threads pane (Discord-like): the list of kind:11 roots
 * with derived reply stats, and one open thread (root + kind:1111 replies). Consumed by both the
 * Compose `ThreadsScreen` and the web `ThreadsScreen`; keyed per group so list <-> detail
 * navigation within a group reuses the same instance.
 */
class ThreadsViewModel(
    private val repo: NostrRepositoryApi,
    val groupId: String,
) : ViewModel() {
    val userMetadata = repo.userMetadata

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    val threads: StateFlow<List<ThreadSummary>> =
        combine(repo.threadRoots, repo.threadReplies) { rootsMap, repliesMap ->
            buildThreadSummaries(rootsMap[groupId] ?: emptyList(), repliesMap[groupId] ?: emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openRootId = MutableStateFlow<String?>(null)
    val openThread: StateFlow<ThreadDetail?> =
        combine(_openRootId, repo.threadRoots, repo.threadReplies) { rootId, rootsMap, repliesMap ->
            rootId ?: return@combine null
            val root = rootsMap[groupId]?.firstOrNull { it.id == rootId } ?: return@combine null
            val replies = (repliesMap[groupId] ?: emptyList())
                .filter { it.threadRootIdTag() == rootId }
                .sortedBy { it.createdAt }
            ThreadDetail(root, replies)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { repo.requestGroupThreads(groupId) }
        viewModelScope.launch {
            // Settle the skeleton as soon as a root arrives, or after a grace period for an empty
            // group (which never emits) - mirrors the chat read's "don't flash empty" guard.
            withTimeoutOrNull(THREAD_LOAD_SETTLE_MS) {
                repo.threadRoots.first { (it[groupId]?.isNotEmpty() == true) }
            }
            _isLoading.value = false
        }
    }

    /** Select the open thread by its kind:11 root id, or null to show the list. */
    fun openThread(rootId: String?) {
        _openRootId.value = rootId
    }

    /** Create a forum thread (kind:11). No-op on blank content. */
    fun createThread(title: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch { repo.createThread(groupId, title.trim(), content.trim()) }
    }

    /** Post a top-level reply (kind:1111) to the open thread. No-op on blank content. */
    fun sendReply(content: String) {
        if (content.isBlank()) return
        val root = openThread.value?.root ?: return
        viewModelScope.launch { repo.sendThreadReply(groupId, root = root, parent = root, content = content.trim()) }
    }

    override fun onCleared() {
        super.onCleared()
        repo.closeThreadSubscriptions(groupId)
    }

    companion object {
        const val THREAD_LOAD_SETTLE_MS = 4_000L
    }
}
