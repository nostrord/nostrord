package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.GroupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HierarchyViewTest {
    private val me = "my-pubkey"

    private fun meta(
        id: String,
        name: String? = id,
        parent: String? = null,
        children: List<String> = emptyList(),
    ) = GroupMetadata(
        id = id,
        name = name,
        about = null,
        picture = null,
        isPublic = true,
        isOpen = true,
        parent = parent,
        children = children,
    )

    private fun childrenOf(groups: List<GroupMetadata>): Map<String, Set<String>> = groups.mapNotNull { g -> g.parent?.let { it to g.id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, v) -> v.toSet() }

    private fun adminsOfAll(groups: List<GroupMetadata>): Map<String, List<String>> = groups.associate { it.id to listOf(me) }

    @Test
    fun `parent candidates exclude self, current parent, descendants and non-admin groups`() {
        val root = meta("root", children = listOf("chan"))
        val chan = meta("chan", parent = "root")
        val other = meta("other")
        val notMine = meta("not-mine")
        val groups = listOf(root, chan, other, notMine)
        val admins = adminsOfAll(groups) - "not-mine"

        val view = hierarchyView("chan", chan, groups, childrenOf(groups), admins, me)
        assertEquals(listOf("other"), view.parentCandidates.map { it.id })
        assertEquals("root", view.parentId)
        assertEquals("root", view.parentName)
    }

    @Test
    fun `group with channels cannot move but keeps candidates listed`() {
        val root = meta("root", children = listOf("chan"))
        val chan = meta("chan", parent = "root")
        val other = meta("other")
        val groups = listOf(root, chan, other)

        val view = hierarchyView("root", root, groups, childrenOf(groups), adminsOfAll(groups), me)
        assertFalse(view.canMove)
        // A descendant is never a parent candidate (cycle).
        assertTrue(view.parentCandidates.none { it.id == "chan" })
    }

    @Test
    fun `child candidates are only childless root groups you admin`() {
        val root = meta("root")
        val withKids = meta("with-kids", children = listOf("kid"))
        val kid = meta("kid", parent = "with-kids")
        val childless = meta("childless")
        val notMine = meta("not-mine")
        val groups = listOf(root, withKids, kid, childless, notMine)
        val admins = adminsOfAll(groups) - "not-mine"

        val view = hierarchyView("root", root, groups, childrenOf(groups), admins, me)
        assertEquals(listOf("childless"), view.childCandidates.map { it.id })
        // A group that has channels can't become a channel; a channel can't either.
        assertTrue(view.childCandidates.none { it.id == "with-kids" || it.id == "kid" })
    }

    @Test
    fun `children follow the declared child-tag order, undeclared last by name`() {
        val root = meta("root", children = listOf("b", "a"))
        val a = meta("a", name = "Alpha", parent = "root")
        val b = meta("b", name = "Beta", parent = "root")
        val z = meta("z", name = "Aaa-undeclared", parent = "root")
        val groups = listOf(root, a, b, z)

        val view = hierarchyView("root", root, groups, childrenOf(groups), adminsOfAll(groups), me)
        assertEquals(listOf("b", "a", "z"), view.childIds)
    }

    @Test
    fun `channel view lists ordered siblings including self, roots have none`() {
        val root = meta("root", children = listOf("c2", "c1"))
        val c1 = meta("c1", parent = "root")
        val c2 = meta("c2", parent = "root")
        val groups = listOf(root, c1, c2)

        val channelView = hierarchyView("c1", c1, groups, childrenOf(groups), adminsOfAll(groups), me)
        assertEquals(listOf("c2", "c1"), channelView.siblingIds)

        val rootView = hierarchyView("root", root, groups, childrenOf(groups), adminsOfAll(groups), me)
        assertEquals(emptyList(), rootView.siblingIds)
    }

    @Test
    fun `missing parent and sibling metadata are reported for fetching`() {
        // Only the channel's own 39000 is loaded; parent and sibling are ghosts.
        val c1 = meta("c1", parent = "root")
        val children = mapOf("root" to setOf("c1", "ghost-sib"))

        val view = hierarchyView("c1", c1, listOf(c1), children, adminsOfAll(listOf(c1)), me)
        assertEquals(setOf("root", "ghost-sib"), view.missingChildMeta.toSet())
    }

    @Test
    fun `movedChildOrder moves within bounds and rejects no-ops`() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedChildOrder(ids, "b", -1))
        assertEquals(listOf("a", "c", "b"), movedChildOrder(ids, "b", +1))
        assertEquals(null, movedChildOrder(ids, "a", -1))
        assertEquals(null, movedChildOrder(ids, "c", +1))
        assertEquals(null, movedChildOrder(ids, "ghost", +1))
    }

    @Test
    fun `missing child metadata is reported for fetching`() {
        val root = meta("root", children = listOf("ghost"))
        val groups = listOf(root)
        val children = mapOf("root" to setOf("ghost"))

        val view = hierarchyView("root", root, groups, children, adminsOfAll(groups), me)
        assertEquals(listOf("ghost"), view.missingChildMeta)
        assertEquals(listOf("ghost"), view.childIds)
    }
}
