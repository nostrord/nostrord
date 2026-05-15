package org.nostr.nostrord.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pubkey doubles as id`() {
        val account = Account(
            pubkey = "a".repeat(64),
            label = "Account 1",
            authMethod = AuthMethod.LOCAL,
            addedAt = 1_700_000_000_000L,
        )
        assertEquals(account.pubkey, account.id)
    }

    @Test
    fun `account serializes round-trip preserving every field`() {
        val original = Account(
            pubkey = "b".repeat(64),
            label = "My bunker",
            authMethod = AuthMethod.BUNKER,
            addedAt = 1_700_000_000_000L,
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Account>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `account list serializes round-trip`() {
        val list = listOf(
            Account("a".repeat(64), "Local 1", AuthMethod.LOCAL, 1L),
            Account("b".repeat(64), "Bunker 1", AuthMethod.BUNKER, 2L),
            Account("c".repeat(64), "Extension", AuthMethod.NIP07, 3L),
        )
        val encoded = json.encodeToString(list)
        val decoded = json.decodeFromString<List<Account>>(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `auth method values cover all login paths`() {
        // Guards against accidentally dropping a variant. Each path in AuthManager
        // (local key, bunker, NIP-07) corresponds to one enum value.
        assertEquals(3, AuthMethod.entries.size)
    }
}
