package org.nostr.nostrord.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.saveLastActiveAt
import org.nostr.nostrord.utils.epochSeconds
import kotlin.concurrent.Volatile

/**
 * Isolated runtime context for a single active Nostr account.
 *
 * Created by [AccountSessionFactory] and atomically installed by
 * [ActiveAccountManager.activate], which cancels the previous session.
 * On [cancel] the [scope] is cancelled (stopping all per-account jobs)
 * and [signer].dispose() runs (zeroing key material). [sessionToken] is
 * a monotonically increasing counter — long-running coroutines on the
 * app scope must capture it at launch time and check it before writing
 * to StateFlows, so stale relay events don't bleed into the new session.
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        // One last write so the catch-up `since` reflects the moment of switch,
        // not the most recent 30s tick.
        try {
            SecureStorage.saveLastActiveAt(pubkey, epochSeconds())
        } catch (_: Exception) {
        }
        try {
            signer.dispose()
        } catch (_: Exception) {
        }
        scope.cancel("AccountSession($accountId) cancelled on account switch")
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
