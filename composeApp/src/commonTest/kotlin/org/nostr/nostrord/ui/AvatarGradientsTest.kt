package org.nostr.nostrord.ui

import org.nostr.nostrord.ui.theme.AvatarGradients
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Expected values computed by running the prototype's JS (nostrord-design-alt
 * src/lib/avatar.ts) under node for the same seeds, locking the cross-platform
 * colour identity: any drift from the prototype's hash/LCG breaks these.
 */
class AvatarGradientsTest {
    @Test
    fun `user gradient matches the prototype output`() {
        val g = AvatarGradients.user("abc")
        assertEquals(352, g.start.hue)
        assertEquals(66, g.end.hue)
        assertEquals(231, g.angleDeg)
        assertEquals(78, g.sheenX)

        val pk = AvatarGradients.user("e9c4a1")
        assertEquals(105, pk.start.hue)
        assertEquals(195, pk.end.hue)
        assertEquals(212, pk.angleDeg)
        assertEquals(28, pk.sheenX)
    }

    @Test
    fun `group gradient matches the prototype output`() {
        val g = AvatarGradients.group("abc")
        assertEquals(352, g.c1.hue)
        assertEquals(164, g.c2.hue)
        assertEquals(352, g.c3.hue)
        assertEquals(323, g.fromDeg)

        val other = AvatarGradients.group("8c3b1f9d")
        assertEquals(229, other.c1.hue)
        assertEquals(29, other.c2.hue)
        assertEquals(58, other.fromDeg)
    }

    @Test
    fun `empty seed falls back to anon like the prototype`() {
        val g = AvatarGradients.user("")
        assertEquals(148, g.start.hue)
        assertEquals(247, g.end.hue)
        assertEquals(240, g.angleDeg)
        assertEquals(30, g.sheenX)
        assertEquals(g, AvatarGradients.user("anon"))
    }
}
