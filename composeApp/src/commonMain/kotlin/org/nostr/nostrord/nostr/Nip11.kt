package org.nostr.nostrord.nostr

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.network.createNip11HttpClient

/**
 * NIP-11: Relay Information Document
 *
 * Fetched via HTTP GET to the relay URL (wss:// → https://) with
 * Accept: application/nostr+json header.
 *
 * https://github.com/nostr-protocol/nips/blob/master/11.md
 */
@Serializable
data class Nip11RelayInfo(
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val pubkey: String? = null,
    val contact: String? = null,
    val supportedNips: List<Int> = emptyList(),
    val software: String? = null,
    val version: String? = null,
    val authRequired: Boolean? = null,
    val paymentRequired: Boolean? = null,
)

private val nip11HttpClient by lazy { createNip11HttpClient() }

/** Converts a WebSocket relay URL to its HTTPS equivalent for the NIP-11 fetch. */
fun relayUrlToHttps(relayUrl: String): String =
    relayUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")

/** Returns true if the icon URL is non-blank and uses HTTPS. */
fun isValidIconUrl(url: String?): Boolean =
    !url.isNullOrBlank() && url.startsWith("https://")

/**
 * Fetches NIP-11 relay info for [relayUrl].
 *
 * Treats relay responses as untrusted input:
 * - Rejects explicit text/html before attempting JSON parse
 * - Wraps all parsing in try/catch — a broken relay never crashes the app
 * - Icon is only used if it starts with https://
 *
 * Returns null on any failure; UI always falls back to text/identicon.
 */
suspend fun fetchNip11RelayInfo(relayUrl: String): Nip11RelayInfo? {
    val httpUrl = relayUrlToHttps(relayUrl)
    return try {
        val response = nip11HttpClient.get(httpUrl) {
            header(HttpHeaders.Accept, "application/nostr+json")
        }
        if (!response.status.isSuccess()) {
            println("[NIP-11] HTTP ${response.status} for $httpUrl")
            return null
        }
        // Reject obvious HTML pages before wasting time parsing them
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        if (contentType.contains("text/html", ignoreCase = true)) {
            println("[NIP-11] HTML response rejected for $httpUrl (Content-Type: $contentType)")
            return null
        }
        val body = response.bodyAsText()
        val obj = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            println("[NIP-11] Not JSON at $httpUrl — ${body.take(80)}")
            return null
        }
        val limitation = obj["limitation"]?.jsonObject
        val icon = obj["icon"]?.jsonPrimitive?.contentOrNull?.takeIf { isValidIconUrl(it) }
        val info = Nip11RelayInfo(
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            icon = icon,
            pubkey = obj["pubkey"]?.jsonPrimitive?.contentOrNull,
            contact = obj["contact"]?.jsonPrimitive?.contentOrNull,
            supportedNips = obj["supported_nips"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
            software = obj["software"]?.jsonPrimitive?.contentOrNull,
            version = obj["version"]?.jsonPrimitive?.contentOrNull,
            authRequired = limitation?.get("auth_required")?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            paymentRequired = limitation?.get("payment_required")?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
        )
        println("[NIP-11] OK $httpUrl → name=${info.name} icon=${info.icon}")
        info
    } catch (e: Exception) {
        println("[NIP-11] Error fetching $httpUrl: ${e::class.simpleName}: ${e.message}")
        null
    }
}
