package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.fetchNip11RelayInfo
import org.nostr.nostrord.nostr.nip11RelayInfoMapSerializer
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getRelayMetadataFetchedAt
import org.nostr.nostrord.storage.saveRelayMetadataFetchedAt
import org.nostr.nostrord.utils.epochSeconds

/**
 * Fetches and caches NIP-11 relay metadata for each relay URL.
 *
 * Call [fetch] whenever a new relay URL is encountered; results accumulate in [relayMetadata].
 *
 * Retry strategy:
 * - Successfully fetched URLs are skipped on subsequent calls (stored in [succeeded]).
 * - In-flight URLs are deduplicated via [inProgress] (guarded by [mutex]).
 * - Failed fetches are retried automatically with exponential backoff (10 s → 5 min cap).
 *   Unlike a hard MAX_RETRIES cap, this approach recovers from transient startup failures
 *   (e.g., network not ready) without permanently blacklisting the relay for the session.
 *
 * Thread safety:
 * All mutations to [succeeded] and [inProgress] are serialised through [mutex] so concurrent
 * [fetch] calls from multiple coroutines on [Dispatchers.Default] cannot produce duplicates.
 */
class RelayMetadataManager(
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata.asStateFlow()

    // Relays whose NIP-11 HTTP fetch exhausted all retries: the document host is
    // unreachable. Cleared if a later fetch succeeds. Used (with socket reachability)
    // to hide groups on dead relays from the discovery surfaces.
    private val _failedRelays = MutableStateFlow<Set<String>>(emptySet())
    val failedRelays: StateFlow<Set<String>> = _failedRelays.asStateFlow()

    // URLs that resolved successfully — never re-fetch these
    private val succeeded = mutableSetOf<String>()

    // URLs currently being fetched — prevents duplicate concurrent requests
    private val inProgress = mutableSetOf<String>()

    // Serialises all reads/writes to [succeeded], [inProgress] and [fetchedAt]
    private val mutex = Mutex()

    // Per-relay last-successful-fetch epoch seconds, hydrated from storage. A relay whose
    // cached document is still within the soft TTL skips the network fetch entirely.
    private val fetchedAt = mutableMapOf<String, Long>()
    private val fetchedAtSerializer = MapSerializer(String.serializer(), Long.serializer())

    companion object {
        // Maximum number of retries before giving up for the session. High enough that
        // transient startup failures (network not ready, slow server) always get resolved,
        // but bounded so a permanently unreachable relay doesn't spin forever.
        private const val MAX_RETRIES = 10

        // Base backoff for the first retry; doubles each time, capped at BACKOFF_CAP_MS.
        private const val BACKOFF_BASE_MS = 10_000L
        private const val BACKOFF_CAP_MS = 5 * 60_000L // 5 minutes

        // Soft TTL: a relay's NIP-11 document is re-fetched at most once a day. Icons and
        // capabilities still refresh, just not on every launch.
        internal const val SOFT_TTL_SECONDS = 24 * 60 * 60L

        /** True if a cached document fetched at [fetchedAtSeconds] is still within the soft TTL. */
        internal fun isWithinSoftTtl(
            fetchedAtSeconds: Long?,
            nowSeconds: Long,
        ): Boolean {
            if (fetchedAtSeconds == null) return false
            return nowSeconds - fetchedAtSeconds < SOFT_TTL_SECONDS
        }
    }

    /**
     * Pre-populates the StateFlow from storage so icons appear immediately on startup,
     * without waiting for the network fetch. Must be called AFTER [SecureStorage.preloadMetadata]
     * on web targets, since IndexedDB reads are async there and the cache would otherwise be empty.
     *
     * Intentionally does NOT add the URLs to `succeeded` so that fetch() still runs once per
     * session and picks up any changes (updated icon URL, new name, etc.).
     */
    fun restoreFromCache() {
        try {
            val cached = SecureStorage.getRelayMetadata()
            if (!cached.isNullOrBlank()) {
                val map = json.decodeFromString(nip11RelayInfoMapSerializer, cached)
                if (map.isNotEmpty()) {
                    _relayMetadata.value = map
                }
            }
        } catch (_: Exception) {
            // Corrupted cache — start fresh
        }
        try {
            val ts = SecureStorage.getRelayMetadataFetchedAt()
            if (!ts.isNullOrBlank()) {
                fetchedAt.putAll(json.decodeFromString(fetchedAtSerializer, ts))
            }
        } catch (_: Exception) {
            // Corrupted timestamps — treat every relay as stale and re-fetch.
        }
    }

    fun fetch(relayUrl: String) {
        scope.launch {
            // Check under lock: skip if already succeeded, in-flight, or fresh within the TTL.
            val shouldFetch =
                mutex.withLock {
                    when {
                        succeeded.contains(relayUrl) -> false
                        inProgress.contains(relayUrl) -> false
                        isFreshLocked(relayUrl) -> {
                            // Cached document still fresh: serve it and don't re-fetch this session.
                            succeeded.add(relayUrl)
                            false
                        }
                        else -> {
                            inProgress.add(relayUrl)
                            true
                        }
                    }
                }
            if (!shouldFetch) return@launch

            fetchWithRetry(relayUrl, attempt = 1)
        }
    }

    // Caller must hold [mutex]. Fresh = hydrated metadata present AND fetched within the soft TTL.
    private fun isFreshLocked(relayUrl: String): Boolean = _relayMetadata.value.containsKey(relayUrl) &&
        isWithinSoftTtl(fetchedAt[relayUrl], epochSeconds())

    private fun fetchWithRetry(
        relayUrl: String,
        attempt: Int,
    ) {
        scope.launch {
            // nextAttemptLaunched tracks whether this coroutine passes inProgress
            // responsibility to the next retry coroutine. If this coroutine is cancelled
            // (e.g. by logout calling scope.cancelChildren()), the finally block cleans up
            // inProgress so subsequent fetch() calls are not permanently blocked.
            var nextAttemptLaunched = false
            try {
                val info = fetchNip11RelayInfo(relayUrl)

                if (info != null) {
                    val now = epochSeconds()
                    val fetchedAtSnapshot =
                        mutex.withLock {
                            inProgress.remove(relayUrl)
                            succeeded.add(relayUrl)
                            fetchedAt[relayUrl] = now
                            fetchedAt.toMap()
                        }
                    _failedRelays.update { it - relayUrl }
                    val updated = _relayMetadata.value + (relayUrl to info)
                    _relayMetadata.value = updated
                    try {
                        SecureStorage.saveRelayMetadata(json.encodeToString(nip11RelayInfoMapSerializer, updated))
                        SecureStorage.saveRelayMetadataFetchedAt(json.encodeToString(fetchedAtSerializer, fetchedAtSnapshot))
                    } catch (_: Exception) {
                        // Non-critical — cache write failure doesn't break anything
                    }
                } else {
                    if (attempt < MAX_RETRIES) {
                        // Exponential backoff: 10s, 20s, 40s, 80s … cap at 5 min
                        val backoffMs = minOf(BACKOFF_BASE_MS * (1L shl minOf(attempt - 1, 8)), BACKOFF_CAP_MS)
                        delay(backoffMs)
                        // Re-check under lock: another coroutine may have succeeded in the meantime
                        val stillNeeded = mutex.withLock { !succeeded.contains(relayUrl) }
                        if (stillNeeded) {
                            nextAttemptLaunched = true
                            fetchWithRetry(relayUrl, attempt + 1)
                        } else {
                            mutex.withLock { inProgress.remove(relayUrl) }
                        }
                    } else {
                        // Retries exhausted: the NIP-11 host is unreachable for this session.
                        mutex.withLock { inProgress.remove(relayUrl) }
                        _failedRelays.update { it + relayUrl }
                    }
                }
            } finally {
                // If this coroutine was cancelled (e.g. logout) before handing off to the next
                // retry, remove relayUrl from inProgress so future fetch() calls are not blocked.
                if (!nextAttemptLaunched) {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (!succeeded.contains(relayUrl)) {
                                inProgress.remove(relayUrl)
                            }
                        }
                    }
                }
            }
        }
    }

    fun fetchAll(relayUrls: Collection<String>) {
        relayUrls.forEach { fetch(it) }
    }
}
