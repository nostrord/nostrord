package org.nostr.nostrord.network.upload

/**
 * Decode the pixel dimensions of an encoded image from its raw bytes, client-side.
 *
 * Used as a fallback so an upload always carries a NIP-68 `dim` even when the host does not
 * report one: without it, the receiving clients cannot reserve the image box and the chat feed
 * shifts on every load. Returns (width, height), or null when the bytes are not a decodable
 * image (the caller then simply omits `dim`). Implementations do a bounds-only decode where the
 * platform allows it, off the main thread.
 */
expect suspend fun decodeImageDimensions(bytes: ByteArray, mimeType: String?): Pair<Int, Int>?
