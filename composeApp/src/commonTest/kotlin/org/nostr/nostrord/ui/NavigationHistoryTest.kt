package org.nostr.nostrord.ui

import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.HomeRoute
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.NotificationsRoute
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.navigation.SettingsRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationHistoryTest {
    @Test
    fun `starts at home with no back or forward`() {
        val h = NavigationHistory()
        assertNull(h.current)
        assertFalse(h.canGoBack)
        assertFalse(h.canGoForward)
    }

    @Test
    fun `navigate to a new route pushes and enables back`() {
        val h = NavigationHistory()
        h.navigate(UserRoute("a"))
        assertEquals(UserRoute("a"), h.current)
        assertTrue(h.canGoBack)
        assertFalse(h.canGoForward)
    }

    @Test
    fun `navigate to the same route key does not grow the stack`() {
        val h = NavigationHistory()
        h.navigate(RelayRoute("wss://r"))
        h.navigate(RelayRoute("wss://r"))
        // Only one entry was added: a single back lands on Home and exhausts it.
        assertNull(h.back())
        assertFalse(h.canGoBack)
    }

    @Test
    fun `navigating the same group with a new message id replaces in place`() {
        val h = NavigationHistory()
        h.navigate(GroupRoute("wss://r", "g"))
        h.navigate(GroupRoute("wss://r", "g", messageId = "m1"))
        // Same routeKey (messageId is a one-shot), so it replaced rather than pushed.
        assertEquals(GroupRoute("wss://r", "g", messageId = "m1"), h.current)
        assertNull(h.back()) // back reaches Home, proving only one group entry exists
    }

    @Test
    fun `chat and threads list and a thread are distinct history entries`() {
        val h = NavigationHistory()
        val chat = GroupRoute("wss://r", "g")
        val threads = chat.copy(view = GroupView.Threads)
        val thread = threads.copy(threadRootId = "root1")
        h.navigate(chat)
        h.navigate(threads)
        h.navigate(thread)
        assertEquals(thread, h.current)
        // Back walks the panes in reverse, then out to Home.
        assertEquals(threads, h.back())
        assertEquals(chat, h.back())
        assertNull(h.back())
        assertFalse(h.canGoBack)
        // Forward replays them.
        assertEquals(chat, h.forward())
        assertEquals(threads, h.forward())
        assertEquals(thread, h.forward())
        assertFalse(h.canGoForward)
    }

    @Test
    fun `re-opening the same thread replaces in place`() {
        val h = NavigationHistory()
        val thread = GroupRoute("wss://r", "g", view = GroupView.Threads, threadRootId = "root1")
        h.navigate(thread)
        // Same view + threadRootId, only the one-shot messageId differs, so it replaces.
        h.navigate(thread.copy(messageId = "ignored"))
        assertNull(h.back()) // back reaches Home: only one thread entry exists
        assertFalse(h.canGoBack)
    }

    @Test
    fun `navigateBackOr pops when the previous entry matches`() {
        val h = NavigationHistory()
        val threads = GroupRoute("wss://r", "g", view = GroupView.Threads)
        val thread = threads.copy(threadRootId = "root1")
        h.navigate(threads)
        h.navigate(thread)
        // Back-arrow from the thread to the list: the list is the previous entry, so pop (no dup).
        h.navigateBackOr(threads)
        assertEquals(threads, h.current)
        assertTrue(h.canGoForward) // the thread stays forward, not duplicated behind us
        assertEquals(thread, h.forward())
    }

    @Test
    fun `navigateBackOr pushes when there is no matching previous entry`() {
        val h = NavigationHistory()
        val threads = GroupRoute("wss://r", "g", view = GroupView.Threads)
        val thread = threads.copy(threadRootId = "root1")
        h.seedDeepLink(thread) // deep link straight to a thread; no list behind it
        h.navigateBackOr(threads)
        assertEquals(threads, h.current)
        assertEquals(thread, h.back()) // pushed, so back returns the thread (not Home)
    }

    @Test
    fun `back and forward move the cursor`() {
        val h = NavigationHistory()
        h.navigate(UserRoute("a"))
        h.navigate(UserRoute("b"))
        assertEquals(UserRoute("a"), h.back())
        assertNull(h.back())
        assertFalse(h.canGoBack)
        assertEquals(UserRoute("a"), h.forward())
        assertEquals(UserRoute("b"), h.forward())
        assertFalse(h.canGoForward)
    }

    @Test
    fun `back at the start and forward at the end are no-ops`() {
        val h = NavigationHistory()
        assertNull(h.back())
        assertNull(h.forward())
        h.navigate(UserRoute("a"))
        assertEquals(UserRoute("a"), h.forward())
        assertEquals(UserRoute("a"), h.current)
    }

    @Test
    fun `push after back truncates forward history`() {
        val h = NavigationHistory()
        h.navigate(UserRoute("a"))
        h.navigate(UserRoute("b"))
        h.back()
        assertTrue(h.canGoForward)
        h.navigate(UserRoute("c"))
        assertEquals(UserRoute("c"), h.current)
        assertFalse(h.canGoForward)
        assertEquals(UserRoute("a"), h.back())
    }

    @Test
    fun `reset clears history to home`() {
        val h = NavigationHistory()
        h.navigate(UserRoute("a"))
        h.navigate(SettingsRoute)
        h.reset()
        assertNull(h.current)
        assertFalse(h.canGoBack)
        assertFalse(h.canGoForward)
    }

    @Test
    fun `seedDeepLink puts home under the target so back returns home`() {
        val h = NavigationHistory()
        h.seedDeepLink(GroupRoute("wss://r", "g"))
        assertEquals(GroupRoute("wss://r", "g"), h.current)
        assertTrue(h.canGoBack)
        assertNull(h.back())
    }

    @Test
    fun `seedDeepLink ignores home targets`() {
        val h = NavigationHistory()
        h.seedDeepLink(null)
        assertFalse(h.canGoBack)
        h.seedDeepLink(HomeRoute(HomeTab.Groups))
        assertFalse(h.canGoBack)
        assertNull(h.current)
    }

    @Test
    fun `state flow reflects the current route and flags`() {
        val h = NavigationHistory()
        h.navigate(NotificationsRoute)
        val s = h.state.value
        assertEquals(NotificationsRoute, s.current)
        assertTrue(s.canGoBack)
        assertFalse(s.canGoForward)
    }

    @Test
    fun `history is capped at fifty entries`() {
        val h = NavigationHistory()
        repeat(60) { h.navigate(UserRoute("u$it")) }
        assertEquals(UserRoute("u59"), h.current)
        assertFalse(h.canGoForward)
        var steps = 0
        while (h.canGoBack) {
            h.back()
            steps++
        }
        // 50 entries retained => 49 back-steps from newest to oldest.
        assertEquals(49, steps)
    }
}
