package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.nip11RelayInfoMapSerializer
import org.nostr.nostrord.nostr.fetchNip11RelayInfo
import org.nostr.nostrord.storage.SecureStorage

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
class RelayMetadataManager(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata.asStateFlow()

    // URLs that resolved successfully — never re-fetch these
    private val succeeded = mutableSetOf<String>()
    // URLs currently being fetched — prevents duplicate concurrent requests
    private val inProgress = mutableSetOf<String>()

    // Serialises all reads/writes to [succeeded] and [inProgress]
    private val mutex = Mutex()

    companion object {
        // Maximum number of retries before giving up for the session. High enough that
        // transient startup failures (network not ready, slow server) always get resolved,
        // but bounded so a permanently unreachable relay doesn't spin forever.
        private const val MAX_RETRIES = 10
        // Base backoff for the first retry; doubles each time, capped at BACKOFF_CAP_MS.
        private const val BACKOFF_BASE_MS = 10_000L
        private const val BACKOFF_CAP_MS = 5 * 60_000L // 5 minutes
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
    }

    fun fetch(relayUrl: String) {
        scope.launch {
            // Check under lock: skip if already succeeded or in-flight
            val shouldFetch = mutex.withLock {
                when {
                    succeeded.contains(relayUrl) -> false
                    inProgress.contains(relayUrl) -> false
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

    private fun fetchWithRetry(relayUrl: String, attempt: Int) {
        scope.launch {
            // nextAttemptLaunched tracks whether this coroutine passes inProgress
            // responsibility to the next retry coroutine. If this coroutine is cancelled
            // (e.g. by logout calling scope.cancelChildren()), the finally block cleans up
            // inProgress so subsequent fetch() calls are not permanently blocked.
            var nextAttemptLaunched = false
            try {
                val info = fetchNip11RelayInfo(relayUrl)

                if (info != null) {
                    mutex.withLock {
                        inProgress.remove(relayUrl)
                        succeeded.add(relayUrl)
                    }
                    val updated = _relayMetadata.value + (relayUrl to info)
                    _relayMetadata.value = updated
                    try {
                        SecureStorage.saveRelayMetadata(json.encodeToString(nip11RelayInfoMapSerializer, updated))
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
                        mutex.withLock { inProgress.remove(relayUrl) }
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
