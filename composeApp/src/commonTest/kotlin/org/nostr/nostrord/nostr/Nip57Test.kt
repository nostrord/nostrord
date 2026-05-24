package org.nostr.nostrord.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip57Test {
    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // lightningAddressToUrl
    // -------------------------------------------------------------------------

    @Test
    fun `lightning address maps to well-known lnurlp url`() {
        assertEquals(
            "https://walletofsatoshi.com/.well-known/lnurlp/alice",
            Nip57.lightningAddressToUrl("alice@walletofsatoshi.com"),
        )
    }

    @Test
    fun `lightning address trims and is case-preserving on name`() {
        assertEquals(
            "https://getalby.com/.well-known/lnurlp/Bob",
            Nip57.lightningAddressToUrl("  Bob@getalby.com "),
        )
    }

    @Test
    fun `malformed lightning addresses return null`() {
        assertNull(Nip57.lightningAddressToUrl("noatsign.com"))
        assertNull(Nip57.lightningAddressToUrl("@domain.com"))
        assertNull(Nip57.lightningAddressToUrl("name@"))
        assertNull(Nip57.lightningAddressToUrl("a@b@c.com"))
    }

    // -------------------------------------------------------------------------
    // lnurl encode / decode round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `lnurl encode then decode round-trips the url`() {
        val url = "https://example.com/.well-known/lnurlp/alice"
        val encoded = Nip57.encodeLnurl(url)
        assertTrue(encoded.startsWith("lnurl1"), "expected lnurl hrp, got $encoded")
        assertEquals(url, Nip57.decodeLnurl(encoded))
    }

    @Test
    fun `decodeLnurl rejects non-lnurl bech32`() {
        // An npub is valid bech32 but the wrong hrp.
        assertNull(Nip57.decodeLnurl("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
    }

    // -------------------------------------------------------------------------
    // resolvePayEndpoint
    // -------------------------------------------------------------------------

    @Test
    fun `resolvePayEndpoint prefers lud16 over lud06`() {
        val lud06 = Nip57.encodeLnurl("https://other.com/.well-known/lnurlp/x")
        val (url, _) = Nip57.resolvePayEndpoint(lud16 = "a@b.com", lud06 = lud06)!!
        assertEquals("https://b.com/.well-known/lnurlp/a", url)
    }

    @Test
    fun `resolvePayEndpoint falls back to lud06 when lud16 missing`() {
        val lnurl = Nip57.encodeLnurl("https://c.com/.well-known/lnurlp/y")
        val (url, bech) = Nip57.resolvePayEndpoint(lud16 = null, lud06 = lnurl)!!
        assertEquals("https://c.com/.well-known/lnurlp/y", url)
        assertEquals(lnurl, bech)
    }

    @Test
    fun `resolvePayEndpoint returns null when neither usable`() {
        assertNull(Nip57.resolvePayEndpoint(lud16 = null, lud06 = null))
        assertNull(Nip57.resolvePayEndpoint(lud16 = "", lud06 = ""))
    }

    // -------------------------------------------------------------------------
    // parseLnurlPayParams
    // -------------------------------------------------------------------------

    @Test
    fun `parseLnurlPayParams reads zap-capable service`() {
        val body = """
            {"callback":"https://x.com/cb","minSendable":1000,"maxSendable":100000000,
             "allowsNostr":true,"nostrPubkey":"abc123","commentAllowed":255,"tag":"payRequest"}
        """.trimIndent()
        val p = Nip57.parseLnurlPayParams(body)!!
        assertEquals("https://x.com/cb", p.callback)
        assertEquals(1000L, p.minSendableMsats)
        assertEquals(100_000_000L, p.maxSendableMsats)
        assertTrue(p.allowsNostr)
        assertEquals("abc123", p.nostrPubkey)
        assertEquals(255, p.commentAllowed)
    }

    @Test
    fun `parseLnurlPayParams defaults allowsNostr false`() {
        val p = Nip57.parseLnurlPayParams("""{"callback":"https://x.com/cb","minSendable":1000,"maxSendable":2000}""")!!
        assertTrue(!p.allowsNostr)
        assertNull(p.nostrPubkey)
    }

    @Test
    fun `parseLnurlPayParams returns null without callback`() {
        assertNull(Nip57.parseLnurlPayParams("""{"status":"ERROR","reason":"not found"}"""))
    }

    // -------------------------------------------------------------------------
    // callback parsing
    // -------------------------------------------------------------------------

    @Test
    fun `parseInvoiceFromCallback extracts pr`() {
        assertEquals("lnbc100n1xyz", Nip57.parseInvoiceFromCallback("""{"pr":"lnbc100n1xyz","routes":[]}"""))
    }

    @Test
    fun `parseCallbackError surfaces reason`() {
        assertEquals("amount too low", Nip57.parseCallbackError("""{"status":"ERROR","reason":"amount too low"}"""))
        assertNull(Nip57.parseCallbackError("""{"pr":"lnbc1"}"""))
    }

    // -------------------------------------------------------------------------
    // buildZapRequest
    // -------------------------------------------------------------------------

    @Test
    fun `buildZapRequest produces a kind 9734 with required tags`() {
        val ev = Nip57.buildZapRequest(
            senderPubkey = "sender",
            recipientPubkey = "recipient",
            amountMsats = 21_000L,
            relays = listOf("wss://relay.one", "wss://relay.two"),
            lnurlBech32 = "lnurl1abc",
            comment = "thanks!",
            eventId = "evt123",
            createdAt = 1_700_000_000L,
        )
        assertEquals(9734, ev.kind)
        assertEquals("thanks!", ev.content)
        assertEquals(listOf("amount", "21000"), ev.getTag("amount"))
        assertEquals(listOf("p", "recipient"), ev.getTag("p"))
        assertEquals(listOf("e", "evt123"), ev.getTag("e"))
        assertEquals(listOf("lnurl", "lnurl1abc"), ev.getTag("lnurl"))
        assertEquals(listOf("relays", "wss://relay.one", "wss://relay.two"), ev.getTag("relays"))
    }

    @Test
    fun `buildZapRequest omits e tag for profile zaps`() {
        val ev = Nip57.buildZapRequest(
            senderPubkey = "s",
            recipientPubkey = "r",
            amountMsats = 1000L,
            relays = emptyList(),
            lnurlBech32 = "lnurl1abc",
            comment = "",
            eventId = null,
            createdAt = 1L,
        )
        assertNull(ev.getTag("e"))
        assertNull(ev.getTag("relays"))
    }

    // -------------------------------------------------------------------------
    // parseZapReceipt
    // -------------------------------------------------------------------------

    @Test
    fun `parseZapReceipt reads amount and zapper from embedded request`() {
        val description = """
            {"pubkey":"zapperpk","kind":9734,"tags":[["amount","50000"],["e","msg1"],["p","authorpk"]],"content":"gg"}
        """.trimIndent().replace("\n", "")
        val receipt = """
            {"kind":9735,"pubkey":"lnurlserverpk","tags":[
              ["p","authorpk"],["e","msg1"],
              ["bolt11","lnbc500n1xyz"],
              ["description","${description.replace("\"", "\\\"")}"]
            ],"content":""}
        """.trimIndent().replace("\n", "")
        val obj = json.parseToJsonElement(receipt).jsonObject
        val parsed = Nip57.parseZapReceipt(obj)
        assertNotNull(parsed)
        assertEquals("msg1", parsed.zappedEventId)
        assertEquals("authorpk", parsed.recipientPubkey)
        assertEquals("zapperpk", parsed.zapperPubkey)
        assertEquals(50_000L, parsed.amountMsats)
    }

    @Test
    fun `parseZapReceipt falls back to bolt11 amount`() {
        val receipt = """
            {"kind":9735,"pubkey":"srv","tags":[["e","m"],["p","a"],["bolt11","lnbc10u1pxyz"]],"content":""}
        """.trimIndent().replace("\n", "")
        val parsed = Nip57.parseZapReceipt(json.parseToJsonElement(receipt).jsonObject)!!
        assertEquals(1_000_000L, parsed.amountMsats) // 10u = 10 micro-BTC = 1_000_000 msats
    }

    @Test
    fun `parseZapReceipt rejects wrong kind`() {
        assertNull(Nip57.parseZapReceipt(json.parseToJsonElement("""{"kind":1,"tags":[]}""").jsonObject))
    }

    // -------------------------------------------------------------------------
    // decodeBolt11AmountMsats
    // -------------------------------------------------------------------------

    @Test
    fun `decodeBolt11AmountMsats handles multipliers`() {
        // 2500u = 2500 micro-BTC = 0.0025 BTC = 250_000 sats = 250_000_000 msats
        assertEquals(250_000_000L, Nip57.decodeBolt11AmountMsats("lnbc2500u1pvjluezpp5..."))
        // 20m = 20 milli-BTC = 0.02 BTC = 2_000_000 sats = 2_000_000_000 msats
        assertEquals(2_000_000_000L, Nip57.decodeBolt11AmountMsats("lnbc20m1pvjluez..."))
        // 1n = 1 nano-BTC = 100 msats
        assertEquals(100L, Nip57.decodeBolt11AmountMsats("lnbc1n1pxyz"))
    }

    @Test
    fun `decodeBolt11AmountMsats returns null for amountless or garbage`() {
        assertNull(Nip57.decodeBolt11AmountMsats("lnbc1pvjluez...")) // amountless
        assertNull(Nip57.decodeBolt11AmountMsats("not-an-invoice"))
    }
}
