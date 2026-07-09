package org.nostr.nostrord.ui.screens.dm

import org.nostr.nostrord.network.managers.DmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmChatItemsTest {
    // 2023-11-14 ~19:13 America/Sao_Paulo; mid-day in most CI timezones too, so
    // small offsets never cross midnight.
    private val base = 1_700_000_000L

    private fun msg(id: String, at: Long, mine: Boolean) = DmMessage(
        id = id,
        peerPubkey = "peer",
        senderPubkey = if (mine) "me" else "peer",
        content = "m$id",
        createdAt = at,
        mine = mine,
    )

    private fun messagesOf(items: List<DmChatItem>) = items.filterIsInstance<DmChatItem.Message>()

    private fun separatorsOf(items: List<DmChatItem>) = items.filterIsInstance<DmChatItem.DateSeparator>()

    @Test
    fun one_separator_per_day() {
        val items = buildDmChatItems(
            listOf(
                msg("a", base, mine = false),
                msg("b", base + 60, mine = false),
                msg("c", base + 2 * 86_400, mine = true),
            ),
        )
        val seps = separatorsOf(items)
        assertEquals(2, seps.size)
        assertTrue(seps[0].label != seps[1].label)
        assertTrue(items[0] is DmChatItem.DateSeparator)
    }

    @Test
    fun same_side_within_window_groups() {
        val items = messagesOf(
            buildDmChatItems(
                listOf(
                    msg("a", base, mine = true),
                    msg("b", base + 60, mine = true),
                    msg("c", base + 120, mine = true),
                ),
            ),
        )
        assertEquals(listOf(true, false, false), items.map { it.firstInGroup })
        assertEquals(listOf(false, false, true), items.map { it.lastInGroup })
    }

    @Test
    fun side_switch_breaks_group() {
        val items = messagesOf(
            buildDmChatItems(
                listOf(
                    msg("a", base, mine = false),
                    msg("b", base + 30, mine = true),
                ),
            ),
        )
        assertTrue(items[0].firstInGroup)
        assertTrue(items[0].lastInGroup)
        assertTrue(items[1].firstInGroup)
        assertTrue(items[1].lastInGroup)
    }

    @Test
    fun gap_over_window_breaks_group() {
        val items = messagesOf(
            buildDmChatItems(
                listOf(
                    msg("a", base, mine = true),
                    msg("b", base + DM_GROUP_WINDOW_SECONDS + 1, mine = true),
                ),
            ),
        )
        assertTrue(items[0].lastInGroup)
        assertTrue(items[1].firstInGroup)
    }

    @Test
    fun unsorted_input_is_sorted() {
        val items = messagesOf(
            buildDmChatItems(
                listOf(
                    msg("b", base + 60, mine = true),
                    msg("a", base, mine = false),
                ),
            ),
        )
        assertEquals(listOf("a", "b"), items.map { it.message.id })
    }
}
