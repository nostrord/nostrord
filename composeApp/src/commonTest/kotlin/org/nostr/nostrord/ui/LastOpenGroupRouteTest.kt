package org.nostr.nostrord.ui

import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HomeRoute
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.persistedRouteHash
import org.nostr.nostrord.ui.navigation.restoredRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastOpenGroupRouteTest {
    @Test
    fun `a group round-trips through the persisted slot into its base route`() {
        val hash = persistedRouteHash(GroupRoute("wss://relay.example", "abc123"))
        assertEquals(GroupRoute("wss://relay.example", "abc123"), restoredRoute(hash))
    }

    @Test
    fun `a group persists only its base route dropping deep-link and pane state`() {
        val hash = persistedRouteHash(GroupRoute("wss://r", "g", messageId = "e1", threadRootId = "t1"))
        assertEquals(GroupRoute("wss://r", "g"), restoredRoute(hash))
    }

    @Test
    fun `the default Groups home persists as the empty slot and restores to plain Home`() {
        assertEquals("", persistedRouteHash(null))
        assertEquals("", persistedRouteHash(HomeRoute(HomeTab.Groups)))
        assertNull(restoredRoute(""))
    }

    @Test
    fun `a non-default home tab round-trips through the persisted slot`() {
        val hash = persistedRouteHash(HomeRoute(HomeTab.Friends))
        assertEquals(HomeRoute(HomeTab.Friends), restoredRoute(hash))
    }

    @Test
    fun `other pages are not tracked so the slot is left unchanged`() {
        assertNull(persistedRouteHash(org.nostr.nostrord.ui.navigation.NotificationsRoute))
        assertNull(persistedRouteHash(org.nostr.nostrord.ui.navigation.SettingsRoute))
    }

    @Test
    fun `a null saved slot restores to nothing`() {
        assertNull(restoredRoute(null))
    }

    @Test
    fun `seeding a restored group reopens into the group with home behind it`() {
        val h = NavigationHistory().apply { seedDeepLink(restoredRoute(persistedRouteHash(GroupRoute("wss://r", "g")))) }
        assertEquals(GroupRoute("wss://r", "g"), h.current)
        assertTrue(h.canGoBack)
        assertNull(h.back()) // back returns Home, never leaves the app
    }

    @Test
    fun `seeding an empty saved slot leaves the app at home`() {
        val h = NavigationHistory().apply { seedDeepLink(restoredRoute("")) }
        assertNull(h.current)
        assertFalse(h.canGoBack)
    }
}
