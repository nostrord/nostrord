package org.nostr.nostrord.auth.pomegranate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges the suspend [PomegranatePopups] calls to a native WebView the Compose UI hosts.
 * The service asks for a token/shard via [await]; a mounted `PomegranateAuthHost` observes
 * [current], shows the WebView, and reports the captured value or a cancel back here. One
 * popup at a time (the login and export flows are sequential), so requests are serialized.
 */
internal object PomegranateAuthBridge {
    enum class Mode { Token, Shard }

    class Request(
        val url: String,
        val expectedOrigin: String,
        val mode: Mode,
        val deferred: CompletableDeferred<String>,
    )

    private val mutex = Mutex()
    private val _current = MutableStateFlow<Request?>(null)
    val current: StateFlow<Request?> = _current.asStateFlow()

    suspend fun await(
        url: String,
        expectedOrigin: String,
        mode: Mode,
    ): String = mutex.withLock {
        val deferred = CompletableDeferred<String>()
        _current.value = Request(url, expectedOrigin, mode, deferred)
        try {
            deferred.await()
        } finally {
            _current.value = null
        }
    }

    /** WebView captured a value the current request accepts. */
    fun complete(value: String) {
        _current.value?.deferred?.complete(value)
    }

    /** User dismissed the WebView before it posted a result. */
    fun cancel() {
        _current.value?.deferred?.completeExceptionally(PomegranatePopupClosedException())
    }

    /** WebView could not load or the platform blocked it. */
    fun fail(error: Throwable) {
        _current.value?.deferred?.completeExceptionally(error)
    }
}
