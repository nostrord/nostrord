package org.nostr.nostrord.nostr

/**
 * NIP-51 lists. Only the mute list (kind:10000) is supported: public mutes are `p` tags.
 * The encrypted private section lives in `content` and is passed through untouched on
 * publish, so private items and non-pubkey entries (words, hashtags, threads) added by
 * other clients survive a publish from this one.
 */
object Nip51 {
    const val KIND_MUTE_LIST = 10000

    /** Public muted pubkeys (`p` tags) of a kind:10000 tag list. */
    fun mutedPubkeysFrom(tags: List<List<String>>): Set<String> = tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { it.getOrNull(1)?.takeIf { pk -> pk.isNotBlank() } }
        .toSet()

    /**
     * Tag list for a new kind:10000: [muted] as `p` tags plus every non-`p` tag of
     * [previousTags] (words, hashtags, thread ids) so other clients' entries are kept.
     */
    fun rebuildMuteTags(
        previousTags: List<List<String>>,
        muted: Set<String>,
    ): List<List<String>> = previousTags.filterNot { it.firstOrNull() == "p" } + muted.map { listOf("p", it) }
}
