package org.nostr.nostrord.utils

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ReadableIdTest {
    @Test
    fun generatesLowercaseAsciiOnly() {
        repeat(200) {
            val id = ReadableId.generate()
            assertTrue(id.all { it in 'a'..'z' }, "unexpected char in $id")
        }
    }

    @Test
    fun lengthStaysWithinReadableBounds() {
        repeat(200) {
            val id = ReadableId.generate()
            assertTrue(id.length in 6..15, "unexpected length ${id.length} for $id")
        }
    }

    @Test
    fun deterministicForSeededRandom() {
        val a = ReadableId.generate(random = Random(42))
        val b = ReadableId.generate(random = Random(42))
        assertTrue(a == b, "seeded generation should be deterministic: $a vs $b")
    }

    @Test
    fun collisionsAreRareInSmallSamples() {
        val ids = (1..1000).map { ReadableId.generate() }
        assertTrue(ids.toSet().size > 990, "too many collisions: ${1000 - ids.toSet().size}")
    }
}
