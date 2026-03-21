package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.fetchNip11RelayInfo
import org.nostr.nostrord.storage.SecureStorage

/**
 * Fetches and caches NIP-11 relay metadata for each relay URL.
 *
 * Call [fetch] whenever a new relay URL is encountered; results accumulate in [relayMetadata].
 * Successfully-fetched URLs are skipped on subsequent calls. Failed fetches are retried on the
 * next [fetch] call (e.g. on reconnect or relay switch), up to [MAX_RETRIES] total attempts.
 */
class RelayMetadataManager(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata.asStateFlow()

    // URLs that resolved successfully — never re-fetch these
    private val succeeded = mutableSetOf<String>()
    // URLs currently being fetched — prevents duplicate concurrent requests
    private val inProgress = mutableSetOf<String>()
    // Attempt counters — stop retrying after MAX_RETRIES failures
    private val attempts = mutableMapOf<String, Int>()

    companion object {
        private const val MAX_RETRIES = 3
    }

    init {
        // Pre-populate the StateFlow from storage so icons appear immediately on startup,
        // without waiting for the network fetch. We intentionally do NOT add these URLs to
        // `succeeded` so that fetch() still runs once per session and picks up any changes
        // to relay metadata (e.g. updated icon URL, new name).
        try {
            val cached = SecureStorage.getRelayMetadata()
            if (!cached.isNullOrBlank()) {
                val map = json.decodeFromString<Map<String, Nip11RelayInfo>>(cached)
                if (map.isNotEmpty()) {
                    _relayMetadata.value = map
                }
            }
        } catch (_: Exception) {
            // Corrupted cache — start fresh
        }
    }

    fun fetch(relayUrl: String) {
        if (succeeded.contains(relayUrl)) return
        if (inProgress.contains(relayUrl)) return
        if ((attempts[relayUrl] ?: 0) >= MAX_RETRIES) return
        inProgress.add(relayUrl)
        scope.launch {
            attempts[relayUrl] = (attempts[relayUrl] ?: 0) + 1
            val info = fetchNip11RelayInfo(relayUrl)
            inProgress.remove(relayUrl)
            if (info != null) {
                succeeded.add(relayUrl)
                val updated = _relayMetadata.value + (relayUrl to info)
                _relayMetadata.value = updated
                try {
                    SecureStorage.saveRelayMetadata(json.encodeToString(updated))
                } catch (_: Exception) {
                    // Non-critical — cache write failure doesn't break anything
                }
            }
        }
    }

    fun fetchAll(relayUrls: Collection<String>) {
        relayUrls.forEach { fetch(it) }
    }
}
