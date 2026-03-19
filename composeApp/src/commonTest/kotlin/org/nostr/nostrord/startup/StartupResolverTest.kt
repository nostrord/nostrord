package org.nostr.nostrord.startup

import kotlin.test.Test
import kotlin.test.assertIs

class StartupResolverTest {

    // ============================================================================
    // Not initialized yet
    // ============================================================================

    @Test
    fun `not initialized returns Initializing regardless of login`() {
        assertIs<AppStartState.Initializing>(
            StartupResolver.resolve(isInitialized = false, isLoggedIn = false)
        )
        assertIs<AppStartState.Initializing>(
            StartupResolver.resolve(isInitialized = false, isLoggedIn = true)
        )
    }

    // ============================================================================
    // Initialized but not logged in
    // ============================================================================

    @Test
    fun `initialized and not logged in returns Unauthenticated`() {
        assertIs<AppStartState.Unauthenticated>(
            StartupResolver.resolve(isInitialized = true, isLoggedIn = false)
        )
    }

    // ============================================================================
    // Initialized and logged in
    // ============================================================================

    @Test
    fun `initialized and logged in returns Authenticated`() {
        // AppModule.nostrRepository.getPublicKey() may return null in test env,
        // which is the graceful fallback path — still returns Authenticated(Home).
        val result = StartupResolver.resolve(isInitialized = true, isLoggedIn = true)
        assertIs<AppStartState.Authenticated>(result)
    }

    @Test
    fun `authenticated result defaults to Home when no pubkey`() {
        // When pubkey is null (no session in test), falls back to Home screen.
        val result = StartupResolver.resolve(isInitialized = true, isLoggedIn = true)
        if (result is AppStartState.Authenticated) {
            assertIs<org.nostr.nostrord.ui.Screen.Home>(result.initialScreen)
        }
    }
}
