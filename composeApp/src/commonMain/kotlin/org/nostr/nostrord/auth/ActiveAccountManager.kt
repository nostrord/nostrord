package org.nostr.nostrord.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Single source of truth for which [AccountSession] is currently active.
 *
 * All account-sensitive singletons derive their current identity from here.
 * No global mutable state should hold pubkey, signer, or session-scoped
 * coroutines outside of the active [AccountSession].
 *
 * ## Atomicity
 *
 * [activate] swaps under a mutex: the new session is installed first, then
 * the previous one is cancelled. Readers always see either the old or the
 * new session (never null in between), and the previous signer's key material
 * is zeroed at the same instant its scope is cancelled.
 */
object ActiveAccountManager {
    @Volatile private var tokenCounter: Long = 0L

    // Mutex guards session swaps. Callers (AccountManager.switchAccount,
    // NostrRepository.finishLoginInit) are always in a suspend context so
    // using a Mutex is natural and gives true sequential swap semantics.
    private val mutex = Mutex()

    private val _session = MutableStateFlow<AccountSession?>(null)

    /** The currently active session; null when no account is active. */
    val session: StateFlow<AccountSession?> = _session.asStateFlow()

    /** Shortcut — null when no account is active. */
    val currentPubkey: String? get() = _session.value?.pubkey

    /** The session token for the active session, or 0 when unauthenticated. */
    val currentSessionToken: Long get() = _session.value?.sessionToken ?: 0L

    /**
     * Install [newSession] as the active session and cancel the previous one.
     * The mutex ensures only one switch executes at a time; the previous
     * session's cancel() happens under the lock so the swap is fully committed
     * before the next caller can enter.
     */
    suspend fun activate(newSession: AccountSession) = mutex.withLock {
        val previous = _session.value
        if (previous === newSession) return@withLock // same object, no-op
        _session.value = newSession
        previous?.cancel()
    }

    /**
     * Cancel and remove the active session. Used on logout.
     */
    suspend fun clear() = mutex.withLock {
        val previous = _session.value
        _session.value = null
        previous?.cancel()
    }

    /**
     * Allocate the next session token. Tokens are monotonically increasing
     * so a manager can detect that the session changed between a coroutine
     * launch and a StateFlow write.
     */
    internal fun nextToken(): Long = ++tokenCounter
}
