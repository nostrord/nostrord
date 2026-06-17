package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip17

/** One decrypted NIP-17 chat message, scoped to a conversation with [peerPubkey]. */
@Serializable
data class DmMessage(
    val id: String,
    val peerPubkey: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val mine: Boolean,
)

/** A conversation summary derived from its messages. */
data class DmConversation(
    val peerPubkey: String,
    val lastMessage: String,
    val lastAt: Long,
    val unread: Int = 0,
)

/**
 * NIP-17 direct-message state and decryption. Pure logic + in-memory state: relay I/O (fetching
 * DM relays, subscribing to the inbox, publishing gift wraps) is orchestrated by [NostrRepository],
 * which feeds incoming kind:1059 / kind:10050 events here and publishes the wraps this builds.
 *
 * Standard NIP-17 (identity key, single `p` tag); the self-copy is sealed to self so the sender can
 * read their own outgoing messages across devices.
 */
class DmManager(
    private val scope: CoroutineScope,
) {
    private val _messagesByPeer = MutableStateFlow<Map<String, List<DmMessage>>>(emptyMap())
    val messagesByPeer: StateFlow<Map<String, List<DmMessage>>> = _messagesByPeer.asStateFlow()

    // Read high-water per peer: createdAt of the newest message marked read. Persisted by the repo.
    private val _lastReadByPeer = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastReadByPeer: StateFlow<Map<String, Long>> = _lastReadByPeer.asStateFlow()

    val conversations: StateFlow<List<DmConversation>> =
        combine(_messagesByPeer, _lastReadByPeer) { byPeer, reads ->
            byPeer.entries
                .mapNotNull { (peer, msgs) ->
                    val last = msgs.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                    val lastRead = reads[peer] ?: 0L
                    val unread = msgs.count { !it.mine && it.createdAt > lastRead }
                    DmConversation(peer, last.content, last.createdAt, unread)
                }
                .sortedByDescending { it.lastAt }
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Unread count per peer (incoming messages newer than the read high-water), zero entries dropped. */
    val unreadByPeer: StateFlow<Map<String, Int>> =
        combine(_messagesByPeer, _lastReadByPeer) { byPeer, reads ->
            byPeer
                .mapValues { (peer, msgs) ->
                    val lastRead = reads[peer] ?: 0L
                    msgs.count { !it.mine && it.createdAt > lastRead }
                }
                .filterValues { it > 0 }
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Total unread across all conversations, for the DM nav badge. */
    val totalUnread: StateFlow<Int> =
        unreadByPeer
            .map { it.values.sum() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    // Recipient/own DM relay lists from kind:10050, keyed by pubkey.
    private val _dmRelaysByPubkey = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val dmRelaysByPubkey: StateFlow<Map<String, List<String>>> = _dmRelaysByPubkey.asStateFlow()

    // Dedup: a gift wrap can arrive from several relays; the same rumor can arrive via the
    // recipient wrap and the self wrap.
    private val seenRumorIds = HashSet<String>()

    /** Decrypt an incoming kind:1059 with [signer] (the account) and add the message to its
     *  conversation. No-op on anything that isn't a chat rumor we can unwrap. */
    suspend fun ingestGiftWrap(giftWrap: Event, myPubkey: String, signer: NostrSigner) {
        val unwrapped = Nip17.unwrap(giftWrap, signer) ?: return
        val rumor = unwrapped.rumor
        if (rumor.kind != Nip17.KIND_CHAT) return
        val rumorId = rumor.id ?: return
        val sender = unwrapped.senderPubkey
        // The conversation peer is the other party: for my own (self-copy) messages that's the
        // rumor's `p` recipient; for received messages it's the sender.
        val peer =
            if (sender == myPubkey) {
                rumor.getTag("p")?.getOrNull(1)
            } else {
                sender
            } ?: return

        if (!seenRumorIds.add(rumorId)) return
        val message =
            DmMessage(
                id = rumorId,
                peerPubkey = peer,
                senderPubkey = sender,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = sender == myPubkey,
            )
        addMessage(peer, message)
    }

    /** Optimistically add a just-sent message so the UI shows it before the self-wrap echoes back. */
    fun addOptimistic(rumor: Event, recipientPubkey: String, myPubkey: String) {
        val rumorId = rumor.id ?: return
        if (!seenRumorIds.add(rumorId)) return
        addMessage(
            recipientPubkey,
            DmMessage(
                id = rumorId,
                peerPubkey = recipientPubkey,
                senderPubkey = myPubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                mine = true,
            ),
        )
    }

    private fun addMessage(peer: String, message: DmMessage) {
        _messagesByPeer.update { current ->
            val merged = (current[peer].orEmpty() + message).sortedBy { it.createdAt }
            current + (peer to merged)
        }
    }

    /** Mark a conversation read up to its newest message; clears its unread count. */
    fun markRead(peer: String) {
        val latest = _messagesByPeer.value[peer]?.maxOfOrNull { it.createdAt } ?: return
        _lastReadByPeer.update { it + (peer to maxOf(it[peer] ?: 0L, latest)) }
    }

    /** Restore decrypted messages + read state from disk on login (before the inbox streams). */
    fun hydrate(messages: List<DmMessage>, lastRead: Map<String, Long>) {
        if (messages.isNotEmpty()) {
            messages.forEach { seenRumorIds.add(it.id) }
            _messagesByPeer.value =
                messages
                    .groupBy { it.peerPubkey }
                    .mapValues { (_, msgs) -> msgs.sortedBy { it.createdAt } }
        }
        if (lastRead.isNotEmpty()) _lastReadByPeer.value = lastRead
    }

    /** Flat snapshot of every decrypted message, for persistence. */
    fun allMessages(): List<DmMessage> = _messagesByPeer.value.values.flatten()

    /** Parse a kind:10050 event into the DM relay list for its author. */
    fun ingestDmRelays(event: Event) {
        val relays =
            event.tags
                .filter { it.firstOrNull() == "relay" && it.getOrNull(1)?.isNotBlank() == true }
                .map { it[1] }
        if (relays.isEmpty()) return
        _dmRelaysByPubkey.update { it + (event.pubkey to relays) }
    }

    fun dmRelaysFor(pubkey: String): List<String> = _dmRelaysByPubkey.value[pubkey].orEmpty()

    /** Drop all state on account switch. */
    fun clear() {
        seenRumorIds.clear()
        _messagesByPeer.value = emptyMap()
        _dmRelaysByPubkey.value = emptyMap()
        _lastReadByPeer.value = emptyMap()
    }
}
