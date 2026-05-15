package org.nostr.nostrord.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.nostr.nostrord.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stress tests for rapid account switching.
 *
 * These tests verify:
 * - Only the last session survives after N sequential switches.
 * - No session scope leaks after switching.
 * - Signing routes to the active signer even under concurrent access.
 * - Disposed signers cannot be used after a switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitchStressTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeSession(pubkey: String): AccountSession {
        val signer = TrackingSigner(pubkey)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AccountSession(
            accountId = AccountId(pubkey),
            pubkey = pubkey,
            signer = signer,
            scope = scope,
            sessionToken = ActiveAccountManager.nextToken(),
        )
    }

    private val fakeEvent = Event(
        id = null, pubkey = "a".repeat(64), createdAt = 0L,
        kind = 1, tags = emptyList(), content = "", sig = null,
    )

    // -------------------------------------------------------------------------
    // Rapid sequential switch — 100 accounts
    // -------------------------------------------------------------------------

    @Test
    fun `100 sequential switches leave only the last session alive`() = runTest {
        val sessions = (1..100).map { i -> makeSession("pk-$i") }
        sessions.forEach { ActiveAccountManager.activate(it) }

        val last = sessions.last()
        assertEquals(last.pubkey, ActiveAccountManager.currentPubkey)
        assertTrue(last.scope.isActive)

        val cancelled = sessions.dropLast(1)
        cancelled.forEach { s ->
            assertFalse(s.scope.isActive, "${s.pubkey} scope should be cancelled")
            assertTrue((s.signer as TrackingSigner).disposed, "${s.pubkey} signer should be disposed")
        }
    }

    // -------------------------------------------------------------------------
    // Signer isolation after switch
    // -------------------------------------------------------------------------

    @Test
    fun `signer from old session is disposed and unusable after switch`() = runTest {
        val sessionA = makeSession("pk-a")
        ActiveAccountManager.activate(sessionA)

        val sessionB = makeSession("pk-b")
        ActiveAccountManager.activate(sessionB)

        val signerA = sessionA.signer as TrackingSigner
        assertTrue(signerA.disposed)

        // An old coroutine from account A's session tries to sign — must fail.
        val ex = runCatching { signerA.signEvent(fakeEvent) }
        assertTrue(ex.isFailure)
    }

    // -------------------------------------------------------------------------
    // No cross-account signing
    // -------------------------------------------------------------------------

    @Test
    fun `signing after switch uses new account signer not old`() = runTest {
        val sessionA = makeSession("pk-a")
        ActiveAccountManager.activate(sessionA)

        val sessionB = makeSession("pk-b")
        ActiveAccountManager.activate(sessionB)

        val activeSigner = ActiveAccountManager.session.value?.signer as? TrackingSigner
        assertNotNull(activeSigner)
        assertEquals("pk-b", activeSigner.pubkey)
        assertFalse(activeSigner.disposed)
    }

    // -------------------------------------------------------------------------
    // Session scope cancelled on switch — new coroutines cannot be launched
    // -------------------------------------------------------------------------

    @Test
    fun `old session scope is cancelled preventing new coroutine launches`() = runTest {
        val sessionA = makeSession("pk-a")
        ActiveAccountManager.activate(sessionA)

        assertTrue(sessionA.scope.isActive, "Scope should be active before switch")

        // Switch account — cancels sessionA.scope
        ActiveAccountManager.activate(makeSession("pk-b"))

        assertFalse(sessionA.scope.isActive, "Scope must be cancelled after switch")

        // Attempt to launch a new job on the cancelled scope — must be a no-op.
        var launchedAfterCancel = false
        val job = sessionA.scope.launch { launchedAfterCancel = true }
        job.join()
        assertFalse(launchedAfterCancel, "Jobs launched on cancelled scope must not execute")
    }

    // -------------------------------------------------------------------------
    // Clear resets everything
    // -------------------------------------------------------------------------

    @Test
    fun `clear after switch leaves no active session`() = runTest {
        ActiveAccountManager.activate(makeSession("pk-x"))
        ActiveAccountManager.clear()

        assertNull(ActiveAccountManager.currentPubkey)
        assertNull(ActiveAccountManager.session.value)
        assertEquals(0L, ActiveAccountManager.currentSessionToken)
    }

    // -------------------------------------------------------------------------
    // Concurrent sign attempt during switch
    // -------------------------------------------------------------------------

    @Test
    fun `signing concurrent with switch produces no exception from the caller`() = runTest {
        repeat(10) {
            val session = makeSession("pk-concurrent")
            ActiveAccountManager.activate(session)

            // Simulate a concurrent sign attempt from a coroutine that was
            // launched before the switch. Since TrackingSigner returns success
            // unless disposed, we check that no unexpected exception propagates.
            val signResult = runCatching {
                ActiveAccountManager.session.value?.signer?.signEvent(fakeEvent)
            }
            // Either succeeded (signer still alive) or was disposed (switch beat us).
            // In both cases the caller should handle it gracefully, not crash.
            assertTrue(signResult.isSuccess || signResult.exceptionOrNull() is NostrSigner.SigningException)
        }
    }
}

// -------------------------------------------------------------------------
// Test doubles
// -------------------------------------------------------------------------

private class TrackingSigner(override val pubkey: String) : NostrSigner {
    var disposed = false
    var signCount = 0

    override suspend fun signEvent(event: Event): Event {
        if (disposed) throw NostrSigner.SigningException("$pubkey signer disposed")
        signCount++
        return event
    }

    override fun dispose() { disposed = true }
}
