package org.nostr.nostrord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.nostr.Event
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [AccountSession] lifecycle: cancel propagation to scope and signer.
 */
class AccountSessionTest {

    private fun makeSession(pubkey: String = "a".repeat(64)): AccountSession {
        val signer = FakeDisposableSigner(pubkey)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AccountSession(
            accountId = AccountId(pubkey),
            pubkey = pubkey,
            signer = signer,
            scope = scope,
            sessionToken = 1L,
        )
    }

    @Test
    fun `cancel stops the coroutine scope`() = runTest {
        val session = makeSession()
        assertTrue(session.scope.isActive)
        session.cancel()
        assertFalse(session.scope.isActive)
    }

    @Test
    fun `cancel disposes the signer`() = runTest {
        val session = makeSession()
        val signer = session.signer as FakeDisposableSigner
        assertFalse(signer.disposed)
        session.cancel()
        assertTrue(signer.disposed)
    }

    @Test
    fun `cancel is idempotent`() = runTest {
        val session = makeSession()
        session.cancel()
        session.cancel() // must not throw or double-dispose
        assertTrue((session.signer as FakeDisposableSigner).disposeCount == 1)
    }

    @Test
    fun `signer dispose failure does not prevent scope cancellation`() = runTest {
        val throwingSigner = ThrowingDisposeSigner("a".repeat(64))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccountSession(AccountId("a".repeat(64)), "a".repeat(64), throwingSigner, scope, 1L)

        session.cancel()
        assertFalse(scope.isActive, "Scope must be cancelled even if signer.dispose() throws")
    }
}

private class FakeDisposableSigner(override val pubkey: String) : NostrSigner {
    var disposed = false
    var disposeCount = 0

    override suspend fun signEvent(event: Event): Event = event
    override fun dispose() {
        disposed = true
        disposeCount++
    }
}

private class ThrowingDisposeSigner(override val pubkey: String) : NostrSigner {
    override suspend fun signEvent(event: Event): Event = error("stub")
    override fun dispose() = throw RuntimeException("signer dispose failed")
}
