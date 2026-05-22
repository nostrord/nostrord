package org.nostr.nostrord.ui.screens.group.model

import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the moderation-event classification in [buildChatItems] (issue #66):
 * kind 9000 (put-user) must distinguish "added" from "role changed", and each
 * moderation kind must map to its own [SystemEventType].
 */
class ChatItemSystemEventTest {
    private var nextId = 0

    private fun msg(
        kind: Int,
        pubkey: String = "author",
        createdAt: Long,
        tags: List<List<String>> = emptyList(),
    ) = NostrMessage(
        id = "id-${nextId++}",
        pubkey = pubkey,
        content = "",
        createdAt = createdAt,
        kind = kind,
        tags = tags,
    )

    private fun systemEvents(messages: List<NostrMessage>) = buildChatItems(messages).filterIsInstance<ChatItem.SystemEvent>()

    @Test
    fun putUser_withRole_isRoleChange() {
        val events = systemEvents(
            listOf(msg(9000, createdAt = 1000, tags = listOf(listOf("p", "bob", "admin")))),
        )
        assertEquals(1, events.size)
        assertEquals(SystemEventType.ROLE_CHANGED, events[0].type)
        assertEquals("is now admin", events[0].action)
        assertEquals("bob", events[0].pubkey)
    }

    @Test
    fun putUser_withMultipleRoles_listsThem() {
        val events = systemEvents(
            listOf(msg(9000, createdAt = 1000, tags = listOf(listOf("p", "bob", "admin", "moderator")))),
        )
        assertEquals(SystemEventType.ROLE_CHANGED, events[0].type)
        assertEquals("is now admin, moderator", events[0].action)
    }

    @Test
    fun putUser_withoutRole_isSuppressed() {
        // A no-role 9000 is ambiguous (plain add vs. role removal) and the relay does
        // not serve it back reliably, so it is never displayed (issue #66).
        val events = systemEvents(
            listOf(msg(9000, createdAt = 1000, tags = listOf(listOf("p", "bob")))),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun putUser_withoutRole_afterJoinRequest_isSuppressed() {
        // The relay auto-confirms a join with a no-role kind 9000; only the kind 9021
        // "joined" line should remain.
        val events = systemEvents(
            listOf(
                msg(9021, pubkey = "bob", createdAt = 1000),
                msg(9000, createdAt = 1010, tags = listOf(listOf("p", "bob"))),
            ),
        )
        assertEquals(1, events.size)
        assertEquals(SystemEventType.JOINED, events[0].type)
    }

    @Test
    fun putUser_withRole_afterJoinRequest_isStillShown() {
        // A role change is a real moderation action even right after a join.
        val events = systemEvents(
            listOf(
                msg(9021, pubkey = "bob", createdAt = 1000),
                msg(9000, createdAt = 1010, tags = listOf(listOf("p", "bob", "admin"))),
            ),
        )
        assertEquals(SystemEventType.JOINED, events[0].type)
        assertEquals(SystemEventType.ROLE_CHANGED, events[1].type)
        assertEquals("is now admin", events[1].action)
    }

    @Test
    fun removeUser_isRemoved() {
        val events = systemEvents(
            listOf(msg(9001, createdAt = 1000, tags = listOf(listOf("p", "bob")))),
        )
        assertEquals(SystemEventType.REMOVED, events[0].type)
        assertEquals("was removed from the group", events[0].action)
    }

    @Test
    fun joinRequest_isJoined() {
        val events = systemEvents(listOf(msg(9021, pubkey = "bob", createdAt = 1000)))
        assertEquals(SystemEventType.JOINED, events[0].type)
        assertEquals("joined the group", events[0].action)
        assertEquals("bob", events[0].pubkey)
    }

    @Test
    fun leaveRequest_isLeft() {
        val events = systemEvents(listOf(msg(9022, pubkey = "bob", createdAt = 1000)))
        assertEquals(SystemEventType.LEFT, events[0].type)
        assertEquals("left the group", events[0].action)
    }

    @Test
    fun putUser_removingLastRole_showsOnlyTheAssignment() {
        // Promote then demote: the demotion arrives as a no-role 9000 and is suppressed,
        // so only the original "is now admin" remains (issue #66).
        val events = systemEvents(
            listOf(
                msg(9000, createdAt = 1000, tags = listOf(listOf("p", "bob", "admin"))),
                msg(9000, createdAt = 2000, tags = listOf(listOf("p", "bob"))),
            ),
        )
        assertEquals(1, events.size)
        assertEquals(SystemEventType.ROLE_CHANGED, events[0].type)
        assertEquals("is now admin", events[0].action)
    }

    @Test
    fun putUser_removingOneOfSeveralRoles_showsRemaining() {
        // Removing one of several roles still carries the remaining role(s), so it is
        // shown as a role assignment.
        val events = systemEvents(
            listOf(
                msg(9000, createdAt = 1000, tags = listOf(listOf("p", "bob", "admin", "moderator"))),
                msg(9000, createdAt = 2000, tags = listOf(listOf("p", "bob", "moderator"))),
            ),
        )
        assertEquals("is now admin, moderator", events[0].action)
        assertEquals(SystemEventType.ROLE_CHANGED, events[1].type)
        assertEquals("is now moderator", events[1].action)
    }
}
