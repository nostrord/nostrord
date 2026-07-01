package org.nostr.nostrord.ui.theme

/** An HSL colour as used by the gradient avatar recipes (hue 0-359, sat/light in %). */
data class Hsl(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
)

/**
 * Deterministic gradient avatars seeded by a pubkey or group id (prototype
 * src/lib/avatar.ts). The seeded math lives here so the same seed produces the same
 * colours on every platform: Compose paints the values with Brushes, the web builds
 * CSS gradient strings. Users get a diagonal duotone with a soft top sheen; groups
 * get a conic swirl, so groups read as groups at a glance.
 *
 * The hash (FNV-1a) and the LCG chain match the prototype's JS bit-for-bit: Kotlin
 * Int arithmetic wraps exactly like Math.imul, and Char.code matches charCodeAt.
 */
object AvatarGradients {
    /** Diagonal duotone + sheen for users. [angleDeg] follows the CSS convention (0 = up, clockwise). */
    data class User(
        val start: Hsl,
        val end: Hsl,
        val angleDeg: Int,
        /** Horizontal centre of the top sheen, in % of the avatar width (y is fixed at 12%). */
        val sheenX: Int,
    )

    /** Conic swirl for groups: c1 -> c2 -> c3 -> c1, starting [fromDeg] clockwise from the top. */
    data class Group(
        val c1: Hsl,
        val c2: Hsl,
        val c3: Hsl,
        val fromDeg: Int,
    )

    /**
     * Tri-tone diagonal band for relays: a three-stop linear gradient (start -> mid ->
     * end) at [angleDeg] (CSS convention). The extra middle stop and the ordered hue
     * march set relays apart from the user's two-tone duotone and the group's conic
     * swirl, so a relay avatar reads as a relay at a glance.
     */
    data class Relay(
        val start: Hsl,
        val mid: Hsl,
        val end: Hsl,
        val angleDeg: Int,
    )

    fun user(seed: String): User {
        val rnd = Lcg(fnv1a(seed.ifEmpty { "anon" }))
        val h1 = (rnd.next() * 360).toInt()
        // Analogous-to-complementary spread; angle stays diagonal-ish, never flat horizontal.
        val h2 = (h1 + 40 + (rnd.next() * 80).toInt()) % 360
        val angle = 115 + (rnd.next() * 130).toInt()
        val sheenX = 20 + (rnd.next() * 60).toInt()
        return User(
            start = Hsl(h1, 62, 56),
            end = Hsl(h2, 58, 38),
            angleDeg = angle,
            sheenX = sheenX,
        )
    }

    /**
     * Group banner gradient (prototype groupGradient): the SAME hue pair as the
     * group's conic avatar (same seed + rnd chain), darkened for legible white
     * text, at a fixed 135deg. Each group's banner matches its avatar identity.
     */
    data class Banner(
        val start: Hsl,
        val end: Hsl,
    )

    fun banner(seed: String): Banner {
        val rnd = Lcg(fnv1a(seed.ifEmpty { "anon" }))
        val h1 = (rnd.next() * 360).toInt()
        val h2 = (h1 + 120 + (rnd.next() * 120).toInt()) % 360
        return Banner(start = Hsl(h1, 52, 34), end = Hsl(h2, 48, 16))
    }

    fun group(seed: String): Group {
        val rnd = Lcg(fnv1a(seed.ifEmpty { "anon" }))
        val h1 = (rnd.next() * 360).toInt()
        val h2 = (h1 + 120 + (rnd.next() * 120).toInt()) % 360
        val from = (rnd.next() * 360).toInt()
        return Group(
            c1 = Hsl(h1, 64, 54),
            c2 = Hsl(h2, 70, 44),
            c3 = Hsl(h1, 58, 36),
            fromDeg = from,
        )
    }

    fun relay(seed: String): Relay {
        val rnd = Lcg(fnv1a(seed.ifEmpty { "relay" }))
        val h1 = (rnd.next() * 360).toInt()
        // Tight, ordered hue march for a smooth three-band ramp (vs the user's wide split).
        val h2 = (h1 + 25 + (rnd.next() * 45).toInt()) % 360
        val h3 = (h2 + 25 + (rnd.next() * 45).toInt()) % 360
        val angle = 145 + (rnd.next() * 70).toInt()
        return Relay(
            start = Hsl(h1, 60, 55),
            mid = Hsl(h2, 58, 44),
            end = Hsl(h3, 54, 32),
            angleDeg = angle,
        )
    }

    private fun fnv1a(s: String): Int {
        var h = 0x811C9DC5.toInt() // 2166136261
        for (c in s) {
            h = h xor c.code
            h *= 16777619
        }
        return h
    }

    /** Numerical Recipes LCG over the full unsigned 32-bit range, yielding doubles in [0, 1). */
    private class Lcg(
        seed: Int,
    ) {
        private var s = seed

        fun next(): Double {
            s = s * 1664525 + 1013904223
            return s.toUInt().toDouble() / 4294967296.0
        }
    }
}
