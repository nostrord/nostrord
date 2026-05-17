package org.nostr.nostrord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.nostr.Event
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveAccountManagerTest {
    @AfterTest
    fun teardown() {
        // Ensure each test starts with a clean slate. runTest is synchronous
        // so no suspend needed here; we use the blocking value setter.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeSession(
        pubkey: String,
        token: Long = 1L,
    ): AccountSession {
        val signer = FakeSigner(pubkey)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AccountSession(
            accountId = AccountId(pubkey),
            pubkey = pubkey,
            signer = signer,
            scope = scope,
            sessionToken = token,
        )
    }

    // -------------------------------------------------------------------------
    // Activate
    // -------------------------------------------------------------------------

    @Test
    fun `activate installs session and emits via StateFlow`() = runTest {
        val session = makeSession("pubkey-a")
        ActiveAccountManager.activate(session)

        assertEquals("pubkey-a", ActiveAccountManager.currentPubkey)
        assertEquals("pubkey-a", ActiveAccountManager.session.value?.pubkey)
    }

    @Test
    fun `activate cancels previous session scope`() = runTest {
        val sessionA = makeSession("pubkey-a")
        ActiveAccountManager.activate(sessionA)

        val sessionB = makeSession("pubkey-b")
        ActiveAccountManager.activate(sessionB)

        assertFalse(sessionA.scope.isActive, "Previous session scope should be cancelled")
        assertTrue(sessionB.scope.isActive, "New session scope should still be active")
    }

    @Test
    fun `activate disposes previous signer`() = runTest {
        val signerA = FakeSigner("pubkey-a")
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionA = AccountSession(AccountId("pubkey-a"), "pubkey-a", signerA, scopeA, 1L)

        ActiveAccountManager.activate(sessionA)
        ActiveAccountManager.activate(makeSession("pubkey-b"))

        assertTrue(signerA.disposed, "Previous signer must be disposed after switch")
    }

    @Test
    fun `activate is idempotent for the same session object`() = runTest {
        val session = makeSession("pubkey-a")
        ActiveAccountManager.activate(session)
        // A second activate with the same object should not cancel the session.
        // This protects against a bug where self-activation kills the live session.
        // (Calling activate with the same object is unusual but must not break things.)
        ActiveAccountManager.activate(session)
        assertTrue(session.scope.isActive)
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    @Test
    fun `clear nulls the session`() = runTest {
        ActiveAccountManager.activate(makeSession("pubkey-a"))
        ActiveAccountManager.clear()

        assertNull(ActiveAccountManager.session.value)
        assertNull(ActiveAccountManager.currentPubkey)
        assertEquals(0L, ActiveAccountManager.currentSessionToken)
    }

    @Test
    fun `clear cancels the active session`() = runTest {
        val session = makeSession("pubkey-a")
        ActiveAccountManager.activate(session)
        ActiveAccountManager.clear()

        assertFalse(session.scope.isActive)
    }

    @Test
    fun `clear on already-null state is safe`() = runTest {
        ActiveAccountManager.clear()
        ActiveAccountManager.clear() // should not throw
        assertNull(ActiveAccountManager.session.value)
    }

    // -------------------------------------------------------------------------
    // Session token
    // -------------------------------------------------------------------------

    @Test
    fun `session token increments on each nextToken call`() {
        val t1 = ActiveAccountManager.nextToken()
        val t2 = ActiveAccountManager.nextToken()
        assertTrue(t2 > t1)
    }

    @Test
    fun `currentSessionToken reflects active session`() = runTest {
        val token = ActiveAccountManager.nextToken()
        val session = makeSession("pubkey-a", token)
        ActiveAccountManager.activate(session)

        assertEquals(token, ActiveAccountManager.currentSessionToken)
    }

    // -------------------------------------------------------------------------
    // Rapid sequential switches
    // -------------------------------------------------------------------------

    @Test
    fun `rapid sequential switches leave only the last session active`() = runTest {
        val sessions = (1..10).map { i -> makeSession("pubkey-$i", i.toLong()) }
        sessions.forEach { ActiveAccountManager.activate(it) }

        val last = sessions.last()
        assertEquals(last.pubkey, ActiveAccountManager.currentPubkey)
        assertTrue(last.scope.isActive)
        sessions.dropLast(1).forEach { s ->
            assertFalse(s.scope.isActive, "${s.pubkey} scope should be cancelled")
        }
    }
}

// -------------------------------------------------------------------------
// Test doubles
// -------------------------------------------------------------------------

private class FakeSigner(
    override val pubkey: String,
) : NostrSigner {
    var disposed = false
    var signingThrows: Exception? = null

    override suspend fun signEvent(event: Event): Event {
        if (disposed) throw NostrSigner.SigningException("disposed")
        signingThrows?.let { throw it }
        return event
    }

    override fun dispose() {
        disposed = true
    }
}
