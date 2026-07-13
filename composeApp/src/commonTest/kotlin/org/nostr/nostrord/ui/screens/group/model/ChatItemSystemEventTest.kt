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
    fun duplicateRemoval_ofSameUser_isNotCountedTwice() {
        // An offline-queued 9001 flushed on reconnect can land beside a fresh one for
        // the same user; the merged row must not read "2 members".
        val events = systemEvents(
            listOf(
                msg(9001, createdAt = 1000, tags = listOf(listOf("p", "bob"))),
                msg(9001, createdAt = 1010, tags = listOf(listOf("p", "bob"))),
            ),
        )
        assertEquals(1, events.size)
        assertTrue(events[0].additionalUsers.isEmpty())
        assertEquals("bob", events[0].pubkey)
    }

    @Test
    fun removalsOfDifferentUsers_renderSeparateRows() {
        // Moderation is an audit trail: each removal names its target, never "2 members".
        val events = systemEvents(
            listOf(
                msg(9001, createdAt = 1000, tags = listOf(listOf("p", "bob"))),
                msg(9001, createdAt = 1010, tags = listOf(listOf("p", "carol"))),
            ),
        )
        assertEquals(2, events.size)
        assertEquals("bob", events[0].pubkey)
        assertEquals("carol", events[1].pubkey)
        assertTrue(events.all { it.additionalUsers.isEmpty() })
    }

    @Test
    fun joinsOfDifferentUsers_stillMergeIntoOneRow() {
        val events = systemEvents(
            listOf(
                msg(9021, pubkey = "bob", createdAt = 1000),
                msg(9021, pubkey = "carol", createdAt = 1010),
            ),
        )
        assertEquals(1, events.size)
        assertEquals(listOf("carol"), events[0].additionalUsers)
    }

    @Test
    fun pluralizeSystemAction_agreesWithMembersSubject() {
        assertEquals(
            "were removed from the group",
            org.nostr.nostrord.ui.screens.group.pluralizeSystemAction("was removed from the group"),
        )
        assertEquals(
            "are now admin",
            org.nostr.nostrord.ui.screens.group.pluralizeSystemAction("is now admin"),
        )
        assertEquals(
            "joined the group",
            org.nostr.nostrord.ui.screens.group.pluralizeSystemAction("joined the group"),
        )
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

    @Test
    fun newMessagesDivider_anchorsOnChatNotZap() {
        // A zap (kind 9321) from another user is newer than lastRead but is NOT a chat
        // message, so it must not anchor the "New messages" divider. The divider sits
        // immediately before the first kind:9 chat message (web/native parity guard).
        val items = buildChatItems(
            messages = listOf(
                msg(9321, pubkey = "bob", createdAt = 1000, tags = listOf(listOf("amount", "100"), listOf("p", "alice"))),
                msg(9, pubkey = "bob", createdAt = 2000),
            ),
            lastReadTimestamp = 500,
            currentUserPubkey = "me",
        )
        val dividerIdx = items.indexOfFirst { it is ChatItem.NewMessagesDivider }
        assertTrue(dividerIdx >= 0)
        assertTrue(items[dividerIdx + 1] is ChatItem.Message)
        // The zap was emitted before the divider, proving it did not anchor it.
        assertTrue(items.indexOfFirst { it is ChatItem.ZapEvent } < dividerIdx)
    }

    @Test
    fun newMessagesDivider_skipsOwnMessages() {
        // Own messages never count as unread, so no divider appears for them.
        val items = buildChatItems(
            messages = listOf(msg(9, pubkey = "me", createdAt = 2000)),
            lastReadTimestamp = 500,
            currentUserPubkey = "me",
        )
        assertTrue(items.none { it is ChatItem.NewMessagesDivider })
    }

    @Test
    fun newMessagesDivider_absentWhenAllRead() {
        val items = buildChatItems(
            messages = listOf(msg(9, pubkey = "bob", createdAt = 100)),
            lastReadTimestamp = 500,
            currentUserPubkey = "me",
        )
        assertTrue(items.none { it is ChatItem.NewMessagesDivider })
    }
}
