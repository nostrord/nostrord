package org.nostr.nostrord.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatScrollPolicyTest {
    @Test
    fun entryDefaultsToBottom() {
        val s = ChatScrollPolicy.onEnterGroup()
        assertTrue(s.atBottom)
        assertFalse(s.openedAtDivider)
        assertFalse(s.restored)
        assertFalse(s.sawNotBottom)
        assertTrue(ChatScrollPolicy.shouldPinToBottom(s, isSeeking = false))
    }

    @Test
    fun noDividerKeepsBottomLatch() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = false, isSeeking = false)
        assertNull(d.target)
        assertTrue(d.state.atBottom)
        assertFalse(d.state.openedAtDivider)
    }

    @Test
    fun dividerAlignsOnceAndClearsBottom() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = true, isSeeking = false)
        assertEquals(ScrollEntryTarget.Divider, d.target)
        assertFalse(d.state.atBottom)
        assertTrue(d.state.openedAtDivider)
        assertFalse(ChatScrollPolicy.shouldPinToBottom(d.state, isSeeking = false))

        // Subsequent chunks must NOT re-align (latched).
        val again = ChatScrollPolicy.onItemsChanged(d.state, hasDivider = true, isSeeking = false)
        assertNull(again.target)
        assertEquals(d.state, again.state)
    }

    @Test
    fun seekingSuppressesDividerAndPin() {
        val s = ChatScrollPolicy.onEnterGroup()
        val d = ChatScrollPolicy.onItemsChanged(s, hasDivider = true, isSeeking = true)
        assertNull(d.target)
        assertFalse(d.state.openedAtDivider)
        // Even at bottom, seeking suppresses the pin so the seek owns scrolling.
        assertFalse(ChatScrollPolicy.shouldPinToBottom(d.state, isSeeking = true))
    }

    @Test
    fun bottomReadingIgnoredBeforeRestored() {
        val s = ChatScrollPolicy.onEnterGroup()
        // Streaming chunks before restore must not flip the latch.
        val after = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertEquals(s, after)
        assertTrue(after.atBottom)
    }

    @Test
    fun scrollAwayThenBackRoundTrip() {
        var s = ChatScrollPolicy.onItemsReady(ChatScrollPolicy.onEnterGroup())
        // User scrolls up.
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertFalse(s.atBottom)
        assertTrue(s.sawNotBottom)
        assertTrue(ChatScrollPolicy.isScrolledAway(s))
        // User scrolls back to bottom.
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = true)
        assertTrue(s.atBottom)
        assertFalse(ChatScrollPolicy.isScrolledAway(s))
    }

    @Test
    fun scrolledUpAtRestoreBoundaryShowsFab() {
        // User scrolled up during initial load: the pre-restore reading is dropped.
        var s = ChatScrollPolicy.onEnterGroup()
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false) // dropped (not restored)
        assertTrue(s.atBottom)
        // Restoration completes; the boundary re-evaluation feeds the CURRENT
        // (scrolled-up) reading, which must now flip the latch so the FAB appears.
        s = ChatScrollPolicy.onItemsReady(s)
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = false)
        assertFalse(s.atBottom)
        assertTrue(ChatScrollPolicy.isScrolledAway(s))
    }

    @Test
    fun dividerEntryNotSpuriouslyRepinnedWithoutScrollAway() {
        // Enter at divider (atBottom=false), restored, but no genuine scroll-away yet.
        var s = ChatScrollPolicy.onItemsChanged(
            ChatScrollPolicy.onEnterGroup(),
            hasDivider = true,
            isSeeking = false,
        ).state
        s = ChatScrollPolicy.onItemsReady(s)
        // A spurious "reachedBottom=true" (divider within bottom tolerance) must NOT
        // promote back to bottom, because sawNotBottom is still false.
        s = ChatScrollPolicy.onBottomReadingChanged(s, reachedBottom = true)
        assertFalse(s.atBottom)
        assertTrue(ChatScrollPolicy.isScrolledAway(s))
    }
}
