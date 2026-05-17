package org.nostr.nostrord.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackChainTest {

    @Test
    fun `empty list returns null without invoking activator`() = runTest {
        var calls = 0
        val result = pickFirstSuccess(emptyList<String>()) {
            calls++
            true
        }
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `all candidates fail returns null and tries every one`() = runTest {
        val attempted = mutableListOf<String>()
        val result = pickFirstSuccess(listOf("a", "b", "c")) { candidate ->
            attempted += candidate
            false
        }
        assertNull(result)
        assertEquals(listOf("a", "b", "c"), attempted)
    }

    @Test
    fun `first success short-circuits iteration`() = runTest {
        val attempted = mutableListOf<String>()
        val result = pickFirstSuccess(listOf("a", "b", "c")) { candidate ->
            attempted += candidate
            true
        }
        assertEquals("a", result)
        assertEquals(listOf("a"), attempted)
    }

    @Test
    fun `Nth candidate accepted after N-1 rejections`() = runTest {
        val attempted = mutableListOf<String>()
        val candidates = listOf("a", "b", "c", "d")
        val result = pickFirstSuccess(candidates) { candidate ->
            attempted += candidate
            candidate == "c"
        }
        assertEquals("c", result)
        assertEquals(listOf("a", "b", "c"), attempted)
        assertTrue("d" !in attempted, "Must not call activator after success")
    }

    @Test
    fun `single candidate rejected returns null`() = runTest {
        val result = pickFirstSuccess(listOf(42)) { false }
        assertNull(result)
    }

    @Test
    fun `single candidate accepted returns it`() = runTest {
        val result = pickFirstSuccess(listOf(42)) { true }
        assertEquals(42, result)
    }
}
