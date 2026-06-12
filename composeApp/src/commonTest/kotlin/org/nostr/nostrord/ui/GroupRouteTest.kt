package org.nostr.nostrord.ui

import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.navigation.parseDmHash
import org.nostr.nostrord.ui.navigation.parseGroupHash
import org.nostr.nostrord.ui.navigation.parseHashRoute
import org.nostr.nostrord.ui.navigation.parseUserHash
import org.nostr.nostrord.ui.navigation.toHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupRouteTest {
    @Test
    fun `round-trips a plain relay and group id`() {
        val route = GroupRoute("wss://groups.0xchat.com", "chachi")
        assertEquals("#/g/groups.0xchat.com/chachi", route.toHash())
        assertEquals(route, parseGroupHash(route.toHash()))
    }

    @Test
    fun `round-trips an invite code`() {
        val route = GroupRoute("wss://relay.example.com", "g1", inviteCode = "AB+C/12")
        assertEquals(route, parseGroupHash(route.toHash()))
    }

    @Test
    fun `encodes a path-bearing relay into a single segment`() {
        val route = GroupRoute("wss://example.com/nip29", "id")
        val hash = route.toHash()
        assertEquals("#/g/example.com%2Fnip29/id", hash)
        assertEquals(route, parseGroupHash(hash))
    }

    @Test
    fun `rejects non-group and malformed hashes`() {
        assertNull(parseGroupHash(""))
        assertNull(parseGroupHash("#/login"))
        assertNull(parseGroupHash("#/g/only-one-segment"))
        assertNull(parseGroupHash("#/g//empty"))
    }

    @Test
    fun `dm route round-trips with and without a peer`() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        assertEquals("#/dm", DmRoute(null).toHash())
        assertEquals(DmRoute(null), parseDmHash("#/dm"))
        val route = DmRoute(hex)
        assertEquals(true, route.toHash().startsWith("#/dm/npub1"))
        assertEquals(route, parseDmHash(route.toHash()))
        assertEquals(route, parseDmHash("#/dm/$hex"))
        assertEquals(route, parseHashRoute(route.toHash()))
        assertNull(parseDmHash("#/dm/not-a-key"))
        assertNull(parseDmHash("#/d"))
    }

    @Test
    fun `user route round-trips as npub and accepts hex`() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val route = UserRoute(hex)
        val hash = route.toHash()
        assertEquals(true, hash.startsWith("#/u/npub1"))
        assertEquals(route, parseUserHash(hash))
        assertEquals(route, parseUserHash("#/u/$hex"))
        assertEquals(route, parseHashRoute(hash))
        assertNull(parseUserHash("#/u/not-a-key"))
        assertNull(parseUserHash("#/u/"))
    }
}
