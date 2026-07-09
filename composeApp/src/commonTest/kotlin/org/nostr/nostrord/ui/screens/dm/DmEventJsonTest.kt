package org.nostr.nostrord.ui.screens.dm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.network.managers.DmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmEventJsonTest {
    private fun msg(rumorJson: String? = null, mine: Boolean = false) = DmMessage(
        id = "rumor-id",
        peerPubkey = "peer-pub",
        senderPubkey = if (mine) "my-pub" else "peer-pub",
        content = "hello",
        createdAt = 1_700_000_000L,
        mine = mine,
        rumorJson = rumorJson,
    )

    @Test
    fun uses_stored_rumor_json_verbatim() {
        val stored = """{"id":"rumor-id","kind":14,"content":"hello","tags":[["p","peer-pub"]]}"""
        assertEquals(stored, msg(rumorJson = stored).eventJson())
    }

    @Test
    fun reconstructs_when_rumor_json_absent() {
        val obj = Json.parseToJsonElement(msg(mine = true).eventJson()).jsonObject
        assertEquals("rumor-id", obj["id"]?.jsonPrimitive?.content)
        assertEquals("14", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("hello", obj["content"]?.jsonPrimitive?.content)
        // Own message carries the recipient p tag; sig is absent (rumors are unsigned).
        assertTrue(obj["tags"].toString().contains("peer-pub"))
        assertTrue(!obj.containsKey("sig"))
    }

    @Test
    fun incoming_reconstruction_has_no_tags() {
        val obj = Json.parseToJsonElement(msg(mine = false).eventJson()).jsonObject
        assertEquals("[]", obj["tags"].toString())
    }

    @Test
    fun pretty_json_is_multiline_and_parses_back() {
        val pretty = msg(mine = true).prettyEventJson()
        assertTrue(pretty.contains("\n"))
        assertEquals(
            "rumor-id",
            Json.parseToJsonElement(pretty).jsonObject["id"]?.jsonPrimitive?.content,
        )
    }
}
