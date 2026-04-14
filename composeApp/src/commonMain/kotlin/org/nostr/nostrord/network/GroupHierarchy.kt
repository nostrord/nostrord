package org.nostr.nostrord.network

/**
 * Expand a set of explicitly-joined group ids with descendants that the user
 * can effectively write to via NIP-29 `inherit-members`.
 *
 * A child is considered effectively joined when:
 * - its parent is effectively joined, AND
 * - the parent has `inherit-members` set, AND
 * - the child itself is not `restricted`.
 *
 * This is the set the UI should consult when gating write actions (composer,
 * join prompts, "you must join" empty states). The raw `joinedGroupIds` is
 * still the source of truth for explicit membership and for the sidebar's
 * visual distinction.
 */
fun effectivelyJoinedGroupIds(
    groups: List<GroupMetadata>,
    joinedGroupIds: Set<String>
): Set<String> {
    if (groups.isEmpty()) return joinedGroupIds
    val byId = groups.associateBy { it.id }
    val byParent = groups.groupBy { it.parent }
    val result = joinedGroupIds.toMutableSet()
    val stack = ArrayDeque<String>().apply { joinedGroupIds.forEach { addLast(it) } }
    while (stack.isNotEmpty()) {
        val parentId = stack.removeLast()
        val parent = byId[parentId] ?: continue
        if (!parent.inheritMembers) continue
        byParent[parentId].orEmpty().forEach { child ->
            if (child.restricted) return@forEach
            if (result.add(child.id)) stack.addLast(child.id)
        }
    }
    return result
}
