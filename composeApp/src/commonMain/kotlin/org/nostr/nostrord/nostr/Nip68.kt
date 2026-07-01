package org.nostr.nostrord.nostr

/**
 * NIP-68 (`imeta` tags) parsing shared by both render trees.
 *
 * An `imeta` tag carries metadata about an attached file so the client can pre-size the
 * slot and avoid layout shift when the bitmap finally decodes:
 *
 *   ["imeta", "url https://example.com/img.jpg", "dim 800x600", "m image/jpeg", "thumb …"]
 *
 * Each field after the tag name is a single key-value pair separated by one space. Both the
 * Compose and web chat lists reserve media space from these hints, so the parser lives in
 * commonMain and is the single source of truth for both.
 */
object Nip68 {

    /**
     * Extract `url` -> (width, height) hints from the event's `imeta` tags. Only entries with
     * both a valid `url` and a positive `dim WxH` are returned.
     */
    fun extractImetaDimensions(tags: List<List<String>>): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        for (tag in tags) {
            if (tag.isEmpty() || tag[0] != "imeta") continue
            var url: String? = null
            var dim: Pair<Int, Int>? = null
            for (i in 1 until tag.size) {
                val field = tag[i]
                when {
                    field.startsWith("url ") -> url = field.removePrefix("url ")
                    field.startsWith("dim ") -> {
                        val parts = field.removePrefix("dim ").split("x", limit = 2)
                        if (parts.size == 2) {
                            val w = parts[0].toIntOrNull()
                            val h = parts[1].toIntOrNull()
                            if (w != null && h != null && w > 0 && h > 0) {
                                dim = w to h
                            }
                        }
                    }
                }
            }
            if (url != null && dim != null) {
                result[url] = dim
            }
        }
        return result
    }

    /**
     * Extract `url` -> thumbnail-url hints from the event's `imeta` tags. Falls back to the
     * `image` field when no explicit `thumb` is present.
     */
    fun extractImetaThumbnails(tags: List<List<String>>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (tag in tags) {
            if (tag.isEmpty() || tag[0] != "imeta") continue
            var url: String? = null
            var thumb: String? = null
            for (i in 1 until tag.size) {
                val field = tag[i]
                when {
                    field.startsWith("url ") -> url = field.removePrefix("url ")
                    field.startsWith("thumb ") -> thumb = field.removePrefix("thumb ")
                    field.startsWith("image ") -> if (thumb == null) thumb = field.removePrefix("image ")
                }
            }
            if (url != null && thumb != null) {
                result[url] = thumb
            }
        }
        return result
    }
}
