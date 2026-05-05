package org.nostr.nostrord.ui.screens.group.components

import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionPopupTest {

    private fun member(displayName: String, pubkey: String = "aabbccdd") =
        MemberInfo(pubkey = pubkey, displayName = displayName, picture = null)

    @Test
    fun `bold fraktur name found by plain query`() {
        val result = getFilteredMembers(listOf(member("𝖘𝖊𝖙𝖙𝖔𝖘𝖍𝖎 𝖙𝖔𝖓𝖆𝖒𝖎")), "settoshi")
        assertEquals(1, result.size)
    }

    @Test
    fun `mathematical bold name found by plain query`() {
        val result = getFilteredMembers(listOf(member("𝐬𝐚𝐭𝐨𝐬𝐡𝐢")), "satoshi")
        assertEquals(1, result.size)
    }

    @Test
    fun `mathematical italic name found by plain query`() {
        val result = getFilteredMembers(listOf(member("𝑎𝑙𝑖𝑐𝑒")), "alice")
        assertEquals(1, result.size)
    }

    @Test
    fun `sans-serif bold name found by plain query`() {
        val result = getFilteredMembers(listOf(member("𝗯𝗼𝗯")), "bob")
        assertEquals(1, result.size)
    }

    @Test
    fun `full-width name found by plain query`() {
        val result = getFilteredMembers(listOf(member("ｃａｒｏｌ")), "carol")
        assertEquals(1, result.size)
    }

    @Test
    fun `plain ascii query matches styled name partially`() {
        val result = getFilteredMembers(listOf(member("𝑎𝑙𝑖𝑐𝑒")), "lic")
        assertEquals(1, result.size)
    }

    @Test
    fun `styled query finds plain ascii name`() {
        val result = getFilteredMembers(listOf(member("carol")), "ｃａｒｏｌ")
        assertEquals(1, result.size)
    }

    @Test
    fun `non-matching query returns empty`() {
        val result = getFilteredMembers(listOf(member("𝗯𝗼𝗯")), "carol")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty query returns up to 8 members`() {
        val members = (1..10).map { member("user$it", "pk$it") }
        assertEquals(8, getFilteredMembers(members, "").size)
    }

    @Test
    fun `pubkey prefix still matches`() {
        val result = getFilteredMembers(listOf(member("Alice", pubkey = "deadbeef1234")), "deadbeef")
        assertEquals(1, result.size)
    }

    @Test
    fun `case-insensitive plain ascii match`() {
        val members = listOf(member("Alice"))
        assertEquals(1, getFilteredMembers(members, "alice").size)
        assertEquals(1, getFilteredMembers(members, "ALICE").size)
    }
}
