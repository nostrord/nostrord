package org.nostr.nostrord.nostr

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * NIP-51 lists. Only the mute list (kind:10000) is supported. Public mutes are `p` tags;
 * the private section is a NIP-44 self-encrypted JSON tag array in `content`. New mutes go
 * to the private section; a `content` that can't be decrypted is passed through untouched
 * on publish, so private items and non-pubkey entries (words, hashtags, threads) added by
 * other clients always survive a publish from this one.
 */
object Nip51 {
    const val KIND_MUTE_LIST = 10000

    private val json = Json { ignoreUnknownKeys = true }

    private val tagsSerializer = ListSerializer(ListSerializer(String.serializer()))

    /** The private-section plaintext: a JSON tag array, same shape as the public tags. */
    fun encodeTags(tags: List<List<String>>): String = json.encodeToString(tagsSerializer, tags)

    /** Parse a decrypted private section; null when it isn't a tag array. */
    fun decodeTags(plaintext: String): List<List<String>>? = try {
        json.decodeFromString(tagsSerializer, plaintext)
    } catch (_: Exception) {
        null
    }

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
