package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.utils.Result

/**
 * Derived state of the Manage > Hierarchy tab. Both UIs render this; the filtering
 * rules (single-level hierarchy, cycle guard, admin gate) live only here.
 */
data class HierarchyView(
    val parentId: String?,
    /** Resolved parent name, falling back to the raw id while its metadata loads. Null for roots. */
    val parentName: String?,
    /** Whether this group may be moved under a parent: it must have no channels of its own. */
    val canMove: Boolean,
    /** This group's channels, declared `child`-tag order first, undeclared after by name. */
    val childIds: List<String>,
    /** Root groups this group could move under (you admin them; no cycles). */
    val parentCandidates: List<GroupMetadata>,
    /** Childless root groups you admin that could become channels here. */
    val childCandidates: List<GroupMetadata>,
    /** Channel ids whose kind:39000 hasn't loaded yet (rows render the id; a fetch is due). */
    val missingChildMeta: List<String>,
)

fun hierarchyView(
    groupId: String,
    metadata: GroupMetadata?,
    relayGroups: List<GroupMetadata>,
    childrenByParent: Map<String, Set<String>>,
    groupAdmins: Map<String, List<String>>,
    myPubkey: String?,
): HierarchyView {
    val byId = relayGroups.associateBy { it.id }
    val parentId = metadata?.parent
    val parentName = parentId?.let { pid -> byId[pid]?.name?.takeIf { it.isNotBlank() } ?: pid }

    // Transitive descendants, excluded from candidates to prevent cycles.
    val descendants = HashSet<String>()
    val stack = ArrayDeque(childrenByParent[groupId].orEmpty())
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (descendants.add(id)) stack.addAll(childrenByParent[id].orEmpty())
    }

    // Declared child order (position of the `child` tag) wins; undeclared confirmed
    // children follow, by name — the same ordering the sidebar channelTree uses.
    val declared = metadata?.children.orEmpty().withIndex().associate { (i, id) -> id to i }
    val childIds = childrenByParent[groupId].orEmpty().sortedWith(
        compareBy({ declared[it] ?: Int.MAX_VALUE }, { (byId[it]?.name ?: it).lowercase() }),
    )

    fun isAdmin(id: String) = myPubkey != null && myPubkey in groupAdmins[id].orEmpty()

    // Single-level hierarchy: channels don't have channels. Only ROOT groups you
    // administer are offered as a parent — never this group, its current parent, or a
    // descendant (cycle); a group that still has channels must detach them first.
    val parentCandidates = relayGroups
        .filter { it.parent == null && it.id != groupId && it.id != parentId && it.id !in descendants && isAdmin(it.id) }
        .sortedBy { (it.name ?: it.id).lowercase() }
    val childCandidates = relayGroups
        .filter {
            it.parent == null &&
                childrenByParent[it.id].orEmpty().isEmpty() &&
                it.id != groupId &&
                it.id !in descendants &&
                isAdmin(it.id)
        }
        .sortedBy { (it.name ?: it.id).lowercase() }

    return HierarchyView(
        parentId = parentId,
        parentName = parentName,
        canMove = childIds.isEmpty(),
        childIds = childIds,
        parentCandidates = parentCandidates,
        childCandidates = childCandidates,
        missingChildMeta = childIds.filter { it !in byId },
    )
}

/** Confirm-dialog copy for a hierarchy operation, shared so both UIs read identically. */
data class HierarchyPrompt(val title: String, val message: String, val confirmLabel: String)

fun moveUnderPrompt(groupName: String, parentName: String) = HierarchyPrompt(
    title = "Move group",
    message = "Make \"$groupName\" a channel of \"$parentName\"? Members, messages and settings are kept.",
    confirmLabel = "Move",
)

fun makeRootPrompt(channelName: String, parentName: String?) = HierarchyPrompt(
    title = "Make root group",
    message = "Turn \"$channelName\" into a root group? It leaves ${parentName?.let { "\"$it\"" } ?: "its parent"} and gets its own spot in the rail.",
    confirmLabel = "Make root",
)

fun detachChannelPrompt(channelName: String) = HierarchyPrompt(
    title = "Detach channel",
    message = "Detach \"$channelName\"? It becomes a root group of its own; members, messages and settings are kept.",
    confirmLabel = "Detach",
)

fun addChannelPrompt(groupName: String, parentName: String) = HierarchyPrompt(
    title = "Add channel",
    message = "Make \"$groupName\" a channel of \"$parentName\"? Members, messages and settings are kept.",
    confirmLabel = "Add",
)

/**
 * Re-parent [target] via editGroup: a kind:9002 replaces the WHOLE metadata (PUT), so the
 * event must carry the target's full current state — publishing without its kind:39000
 * cached would clobber the name/flags.
 */
suspend fun reparentGroup(
    repo: NostrRepositoryApi,
    target: GroupMetadata,
    op: GroupManager.ParentOp,
): Result<Unit> = repo.editGroup(
    groupId = target.id,
    name = target.name?.takeIf { it.isNotBlank() } ?: target.id,
    about = target.about,
    isPrivate = !target.isPublic,
    isClosed = !target.isOpen,
    isRestricted = target.isRestricted,
    isHidden = target.isHidden,
    picture = target.picture,
    parentOp = op,
)
