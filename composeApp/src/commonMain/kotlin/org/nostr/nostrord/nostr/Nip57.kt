package org.nostr.nostrord.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * NIP-57 — Lightning Zaps.
 *
 * Pure, platform-agnostic helpers for the zap flow:
 *
 *  1. Resolve a recipient's LNURL-pay endpoint from their kind:0 `lud16`/`lud06`.
 *  2. Parse the LNURL-pay service description.
 *  3. Build the **zap request** (kind 9734) — this is signed by us and sent as a
 *     query param to the LNURL callback, NOT published to a relay.
 *  4. Extract the bolt11 invoice from the callback response.
 *  5. Parse incoming **zap receipts** (kind 9735) — created and published by the
 *     recipient's LNURL server; we only read these to display zap totals.
 */
object Nip57 {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Parsed LNURL-pay service description (the GET to the lnurlp URL). */
    data class LnurlPayParams(
        val callback: String,
        val minSendableMsats: Long,
        val maxSendableMsats: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String?,
        val commentAllowed: Int,
    )

    /** Parsed zap receipt (kind 9735). Amounts are in millisatoshis. */
    data class ZapReceipt(
        val zappedEventId: String?,
        val recipientPubkey: String?,
        val zapperPubkey: String?,
        val amountMsats: Long?,
        /** The paid invoice — uniquely identifies which zap request this settles. */
        val bolt11: String?,
    )

    /**
     * Convert a lightning address (`lud16`, `name@domain`) to its LNURL-pay https URL
     * per LUD-16. Returns null if the address is malformed.
     */
    fun lightningAddressToUrl(address: String): String? {
        val trimmed = address.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0 || at >= trimmed.length - 1) return null
        val name = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (name.isBlank() || domain.isBlank() || '/' in domain || '@' in domain) return null
        return "https://$domain/.well-known/lnurlp/$name"
    }

    /** Decode a bech32 `lnurl1...` (`lud06`) into its https URL. */
    fun decodeLnurl(lnurl: String): String? {
        val decoded = Bech32.decode(lnurl.trim()) ?: return null
        if (!decoded.first.equals("lnurl", ignoreCase = true)) return null
        return decoded.second.decodeToString().takeIf { it.startsWith("http") }
    }

    /** Bech32-encode an https URL as an `lnurl1...` string (the zap request `lnurl` tag). */
    fun encodeLnurl(url: String): String = Bech32.encode("lnurl", url.encodeToByteArray())

    /**
     * Resolve a recipient's LNURL-pay endpoint from their kind:0 fields. Prefers
     * `lud16` (lightning address); falls back to `lud06` (bech32 lnurl).
     *
     * @return `(lnurlPayUrl, lnurlBech32)` or null if neither field is usable.
     */
    fun resolvePayEndpoint(lud16: String?, lud06: String?): Pair<String, String>? {
        lud16?.takeIf { it.isNotBlank() }?.let { addr ->
            lightningAddressToUrl(addr)?.let { url -> return url to encodeLnurl(url) }
        }
        lud06?.takeIf { it.isNotBlank() }?.let { raw ->
            decodeLnurl(raw)?.let { url -> return url to raw.trim().lowercase() }
        }
        return null
    }

    /** Parse an LNURL-pay service description, or null if it is not a pay request. */
    fun parseLnurlPayParams(body: String): LnurlPayParams? = try {
        val obj = lenientJson.parseToJsonElement(body).jsonObject
        val callback = obj["callback"]?.jsonPrimitive?.contentOrNull
        if (callback.isNullOrBlank()) {
            null
        } else {
            LnurlPayParams(
                callback = callback,
                minSendableMsats = obj["minSendable"]?.jsonPrimitive?.longOrNull ?: 1_000L,
                maxSendableMsats = obj["maxSendable"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE,
                allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
                nostrPubkey = obj["nostrPubkey"]?.jsonPrimitive?.contentOrNull,
                commentAllowed = obj["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Extract the bolt11 invoice (`pr`) from an LNURL-pay callback response. */
    fun parseInvoiceFromCallback(body: String): String? = try {
        lenientJson.parseToJsonElement(body).jsonObject["pr"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Return the callback's error reason if it responded with `status: "ERROR"`, else null. */
    fun parseCallbackError(body: String): String? = try {
        val obj = lenientJson.parseToJsonElement(body).jsonObject
        if (obj["status"]?.jsonPrimitive?.contentOrNull?.equals("ERROR", ignoreCase = true) == true) {
            obj["reason"]?.jsonPrimitive?.contentOrNull ?: "LNURL service rejected the request"
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Build an unsigned zap request (kind 9734). The signed, JSON-serialized form is
     * sent to the LNURL callback `nostr` param — it is never published to a relay.
     *
     * @param eventId when zapping a specific event (e.g. a chat message); null for a profile zap.
     */
    fun buildZapRequest(
        senderPubkey: String,
        recipientPubkey: String,
        amountMsats: Long,
        relays: List<String>,
        lnurlBech32: String,
        comment: String,
        eventId: String?,
        createdAt: Long,
    ): Event {
        val tags = buildList {
            if (relays.isNotEmpty()) add(listOf("relays") + relays)
            add(listOf("amount", amountMsats.toString()))
            add(listOf("lnurl", lnurlBech32))
            add(listOf("p", recipientPubkey))
            if (!eventId.isNullOrBlank()) add(listOf("e", eventId))
        }
        return Event(
            pubkey = senderPubkey,
            createdAt = createdAt,
            kind = 9734,
            tags = tags,
            content = comment,
        )
    }

    /**
     * Parse a zap receipt (kind 9735). The receipt embeds the original zap request in its
     * `description` tag — that is the source of truth for the zapper and amount. Falls back
     * to the receipt's own `amount`/`bolt11` tags when the description is absent.
     */
    fun parseZapReceipt(event: JsonObject): ZapReceipt? = try {
        if (event["kind"]?.jsonPrimitive?.intOrNull != 9735) {
            null
        } else {
            val tags = event["tags"]?.jsonArray
                ?.mapNotNull { t -> runCatching { t.jsonArray.map { it.jsonPrimitive.content } }.getOrNull() }
                ?: emptyList()
            fun tag(name: String) = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

            val request = tag("description")
                ?.let { runCatching { lenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            val reqTags = request?.get("tags")?.jsonArray
                ?.mapNotNull { t -> runCatching { t.jsonArray.map { it.jsonPrimitive.content } }.getOrNull() }
                ?: emptyList()
            fun reqTag(name: String) = reqTags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

            val amountMsats = reqTag("amount")?.toLongOrNull()
                ?: tag("amount")?.toLongOrNull()
                ?: tag("bolt11")?.let { decodeBolt11AmountMsats(it) }
            val zapper = request?.get("pubkey")?.jsonPrimitive?.contentOrNull ?: tag("P")
            val zappedEvent = tag("e") ?: reqTag("e")

            ZapReceipt(
                zappedEventId = zappedEvent,
                recipientPubkey = tag("p") ?: reqTag("p"),
                zapperPubkey = zapper,
                amountMsats = amountMsats,
                bolt11 = tag("bolt11"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Decode the amount encoded in a bolt11 invoice's human-readable part, in millisats.
     * Returns null for amountless invoices or malformed input.
     */
    fun decodeBolt11AmountMsats(invoice: String): Long? {
        val s = invoice.trim().lowercase()
        if (!s.startsWith("ln")) return null
        // The bech32 separator is the last '1' — the data charset never contains '1',
        // and the amount precedes the separator.
        val sep = s.lastIndexOf('1')
        if (sep <= 2) return null
        var hrp = s.substring(2, sep) // strip "ln"; e.g. "bc2500u"
        val currency = listOf("bcrt", "bc", "tb", "sb").firstOrNull { hrp.startsWith(it) } ?: return null
        hrp = hrp.substring(currency.length) // e.g. "2500u" (or "" if amountless)
        if (hrp.isEmpty()) return null
        val multChar = hrp.last()
        val hasMultiplier = multChar in "munp"
        val digits = (if (hasMultiplier) hrp.dropLast(1) else hrp).toLongOrNull() ?: return null
        // amount(BTC) = digits * multiplier; 1 BTC = 100_000_000_000 msats.
        return when (if (hasMultiplier) multChar else ' ') {
            ' ' -> digits * 100_000_000_000L
            'm' -> digits * 100_000_000L
            'u' -> digits * 100_000L
            'n' -> digits * 100L
            'p' -> digits / 10L
            else -> null
        }
    }
}
