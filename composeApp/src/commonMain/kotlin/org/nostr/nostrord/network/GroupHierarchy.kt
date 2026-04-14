package org.nostr.nostrord.network

/**
 * Maximum parent-chain depth the client will walk when computing effective
 * membership via `inherit-members`. Matches the bound recommended by NIP-29
 * (small constant such as 8) so pathological or maliciously nested trees
 * can't blow up the walk.
 */
private const val MAX_INHERITANCE_DEPTH = 8

/**
 * Expand a set of explicitly-joined group ids with descendants that the user
 * can effectively write to via NIP-29 `inherit-members`.
 *
 * A child is considered effectively joined when:
 * - its parent is effectively joined, AND
 * - the parent has `inherit-members` set, AND
 * - the child itself is not `restricted`.
 *
 * Walk is capped at [MAX_INHERITANCE_DEPTH] levels below each seed. Cycles
 * are harmless (already-added ids aren't re-enqueued) but the depth cap is
 * still enforced as defense in depth.
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
    // Each stack entry carries the depth traveled below its seed.
    val stack = ArrayDeque<Pair<String, Int>>().apply {
        joinedGroupIds.forEach { addLast(it to 0) }
    }
    while (stack.isNotEmpty()) {
        val (parentId, depth) = stack.removeLast()
        if (depth >= MAX_INHERITANCE_DEPTH) continue
        val parent = byId[parentId] ?: continue
        if (!parent.inheritMembers) continue
        byParent[parentId].orEmpty().forEach { child ->
            if (child.restricted) return@forEach
            if (result.add(child.id)) stack.addLast(child.id to depth + 1)
        }
    }
    return result
}
