package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.fetchNip11RelayInfo

/**
 * Fetches and caches NIP-11 relay metadata for each relay URL.
 *
 * Call [fetch] whenever a new relay URL is encountered; results accumulate in [relayMetadata].
 * Already-fetched URLs are skipped (success or failure).
 */
class RelayMetadataManager(private val scope: CoroutineScope) {

    private val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata.asStateFlow()

    private val fetched = mutableSetOf<String>()

    fun fetch(relayUrl: String) {
        if (fetched.contains(relayUrl)) return
        fetched.add(relayUrl)
        scope.launch {
            val info = fetchNip11RelayInfo(relayUrl) ?: return@launch
            _relayMetadata.value = _relayMetadata.value + (relayUrl to info)
        }
    }

    fun fetchAll(relayUrls: Collection<String>) {
        relayUrls.forEach { fetch(it) }
    }
}
