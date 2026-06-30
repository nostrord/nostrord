package org.nostr.nostrord.ui

import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.lastOpenGroupRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastOpenGroupRouteTest {
    @Test
    fun `decodes a saved relay and group pair into the base group route`() {
        val route = lastOpenGroupRoute("wss://relay.example" to "abc123")
        assertEquals(GroupRoute("wss://relay.example", "abc123"), route)
    }

    @Test
    fun `null saved slot decodes to null so nothing is restored`() {
        assertNull(lastOpenGroupRoute(null))
    }

    @Test
    fun `seeding the decoded route reopens into the group with home behind it`() {
        val h = NavigationHistory().apply { seedDeepLink(lastOpenGroupRoute("wss://r" to "g")) }
        assertEquals(GroupRoute("wss://r", "g"), h.current)
        assertTrue(h.canGoBack)
        assertNull(h.back()) // back returns Home, never leaves the app
    }

    @Test
    fun `seeding an empty saved slot leaves the app at home`() {
        val h = NavigationHistory().apply { seedDeepLink(lastOpenGroupRoute(null)) }
        assertNull(h.current)
        assertFalse(h.canGoBack)
    }
}
