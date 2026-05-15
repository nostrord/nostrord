package org.nostr.nostrord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.saveLastActiveAt
import org.nostr.nostrord.utils.epochSeconds

/**
 * Isolated runtime context for a single active Nostr account.
 *
 * Created by [AccountSessionFactory] and activated atomically by
 * [ActiveAccountManager.activate]. Every account switch:
 *  1. Creates a fresh [AccountSession] with its own [scope] and [signer].
 *  2. Calls [ActiveAccountManager.activate] which atomically installs the new
 *     session and immediately cancels the previous one.
 *  3. Cancelling a session cancels [scope] — stopping all coroutines that were
 *     processing relay events, subscriptions, or message pipelines for the
 *     previous account — and calls [signer].dispose() to zero key material.
 *
 * ## Isolation guarantees
 *
 * - Key material: [signer].dispose() zeroes the private key or disconnects the
 *   remote signer. No other session can call [signer].signEvent() after disposal.
 * - Coroutines: all jobs launched on [scope] receive CancellationException
 *   instantly when [cancel] is called.
 * - Session token: [sessionToken] is a monotonically increasing counter.
 *   Managers that launch coroutines on appScope should capture the token at
 *   launch time and check it before writing to StateFlows, preventing stale
 *   relay events from appearing after an account switch.
 */
class AccountSession(
    val accountId: AccountId,
    val pubkey: String,
    val signer: NostrSigner,
    val scope: CoroutineScope,
    val sessionToken: Long,
) {
    @Volatile private var cancelled = false

    init {
        // Heartbeat: while this session is alive, persist a "last active at"
        // timestamp so the next switch-in for this pubkey can compute a
        // catch-up `since` even if the app crashed before [cancel] ran.
        // Cancellation of [scope] stops the loop automatically.
        //
        // No initial write: `reloadForActiveAccount` reads `lastActiveAt`
        // shortly after this session is created, and must see the PREVIOUS
        // activation's timestamp (so catch-up covers the inactive window).
        // The first heartbeat after HEARTBEAT_INTERVAL_MS will then advance
        // the stored value for the next switch-in or restart.
        scope.launch {
            try {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    SecureStorage.saveLastActiveAt(pubkey, epochSeconds())
                }
            } catch (_: Throwable) {}
        }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        // One last write so the catch-up `since` reflects the moment of switch,
        // not the most recent 30s tick.
        try { SecureStorage.saveLastActiveAt(pubkey, epochSeconds()) } catch (_: Exception) {}
        try { signer.dispose() } catch (_: Exception) {}
        scope.cancel("AccountSession($accountId) cancelled on account switch")
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
