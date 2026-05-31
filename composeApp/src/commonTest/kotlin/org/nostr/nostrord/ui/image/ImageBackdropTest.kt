package org.nostr.nostrord.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageBackdropTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun emptyIsNull() {
        assertNull(decideImageBackdrop(IntArray(0)))
    }

    @Test
    fun fullyOpaqueIsNull() {
        // No transparency -> leave as-is, regardless of darkness.
        val pixels = IntArray(100) { argb(255, 10, 10, 10) }
        assertNull(decideImageBackdrop(pixels))
    }

    @Test
    fun transparentDarkContentWantsOnLight() {
        // Half transparent, opaque pixels are dark -> needs a white backdrop.
        val pixels = IntArray(100) { i ->
            if (i < 50) argb(0, 0, 0, 0) else argb(255, 20, 20, 20)
        }
        assertEquals(ImageBackdrop.OnLight, decideImageBackdrop(pixels))
    }

    @Test
    fun transparentLightContentWantsOnDark() {
        // Half transparent, opaque pixels are light -> needs a dark backdrop.
        val pixels = IntArray(100) { i ->
            if (i < 50) argb(0, 0, 0, 0) else argb(255, 240, 240, 240)
        }
        assertEquals(ImageBackdrop.OnDark, decideImageBackdrop(pixels))
    }

    @Test
    fun belowThresholdTransparencyIsNull() {
        // Under 5% transparent -> treated as opaque.
        val pixels = IntArray(100) { i ->
            if (i < 4) argb(0, 0, 0, 0) else argb(255, 20, 20, 20)
        }
        assertNull(decideImageBackdrop(pixels))
    }

    @Test
    fun allTransparentIsNull() {
        // Transparent but no opaque content to measure -> null.
        val pixels = IntArray(100) { argb(0, 0, 0, 0) }
        assertNull(decideImageBackdrop(pixels))
    }
}
