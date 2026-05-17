package org.nostr.nostrord.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class EventData(
    val zero: Int,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

/**
 * Nostr event with real cryptographic signing
 */
@Serializable
data class Event(
    val id: String? = null,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>> = emptyList(),
    val content: String,
    val sig: String? = null,
) {
    /**
     * Calculate event ID according to NIP-01
     */
    fun calculateId(): String {
        // Cria o array JSON seguindo NIP-01
        val jsonArray =
            buildJsonArray {
                add(JsonPrimitive(0)) // zero
                add(JsonPrimitive(pubkey)) // pubkey
                add(JsonPrimitive(createdAt)) // created_at
                add(JsonPrimitive(kind)) // kind

                // tags como array de arrays
                val tagsArray =
                    buildJsonArray {
                        tags.forEach { tag ->
                            val inner =
                                buildJsonArray {
                                    tag.forEach { add(JsonPrimitive(it)) }
                                }
                            add(inner)
                        }
                    }
                add(tagsArray)

                add(JsonPrimitive(content)) // content
            }

        // Serializa para string compacta
        val jsonString = jsonArray.toString()
        val hash = Crypto.sha256(jsonString)
        return hash.toHexString()
    }

    /**
     * Sign the event with a key pair
     */
    fun sign(keyPair: KeyPair): Event {
        val idHex = calculateId()
        val messageHash = idHex.hexToByteArray() // already SHA-256(JSON)
        val signature = keyPair.signMessage(messageHash)
        return copy(
            id = idHex,
            // pubkey = Crypto.getPublicKeyXOnly(keyPair.privateKey).toHexString(),
            sig = signature.toHexString(),
        )
    }

    /**
     * Verify event signature
     */
    fun verify(): Boolean {
        if (id == null || sig == null) return false
        val expectedId = calculateId()
        if (id != expectedId) return false

        val messageHash = id.hexToByteArray() // already SHA-256(JSON)
        val signatureBytes = sig.hexToByteArray()
        val pubkeyBytes = pubkey.hexToByteArray()
        return Crypto.verifySignature(signatureBytes, messageHash, pubkeyBytes)
    }

    /**
     * Convert event to JsonObject for serialization
     */
    fun toJsonObject(): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put(
            "tags",
            buildJsonArray {
                tags.forEach { tag ->
                    add(
                        buildJsonArray {
                            tag.forEach { tagValue ->
                                add(tagValue)
                            }
                        },
                    )
                }
            },
        )
        put("content", content)
        sig?.let { put("sig", it) }
    }

    /**
     * Convert event to JSON string
     */
    fun toJsonString(): String = toJsonObject().toString()

    // Utility functions
    fun hasTag(tagName: String): Boolean = tags.any { it.isNotEmpty() && it[0] == tagName }

    fun getTag(tagName: String): List<String>? = tags.find { it.isNotEmpty() && it[0] == tagName }

    fun getAllTags(tagName: String): List<List<String>> = tags.filter { it.isNotEmpty() && it[0] == tagName }

    fun getGroupId(): String? = getTag("h")?.getOrNull(1) ?: getTag("d")?.getOrNull(1)
}
