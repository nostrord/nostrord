package org.nostr.nostrord.ui.theme

/**
 * Single source of truth for the shared spacing and corner-radius design language, as
 * unit-agnostic integers (dp on Compose, px on web). Lives in commonMain so the Compose
 * [Spacing] / [NostrordShapes] scales and the web `--space-*` / `--radius-*` custom
 * properties derive from the same numbers.
 *
 * Scope is the generic scale only (the actual design language). Component / layout
 * dimensions that are platform-specific (sidebar widths, avatar sizes, the CSS rule set)
 * stay on their own side.
 *
 * NOTE: the web CSS currently uses ad-hoc literal px for radii (6/9/10/24px and others)
 * that predate this scale and do not all map onto it. Migrating those literals to
 * `var(--radius-*)` is a visual-review task, not a mechanical one, so it is intentionally
 * not done here. New web CSS should use the injected vars.
 */
object DimenTokens {
    // Spacing scale (4-unit base)
    const val spaceXxs = 2
    const val spaceXs = 4
    const val spaceSm = 8
    const val spaceMd = 12
    const val spaceLg = 16
    const val spaceXl = 20
    const val spaceXxl = 24
    const val spaceXxxl = 32

    // Corner-radius scale
    const val radiusNone = 0
    const val radiusSmall = 4
    const val radiusMedium = 8
    const val radiusLarge = 12
    const val radiusXLarge = 16

    /** Effectively-full rounding for pills / avatars (Compose uses CircleShape for true circles). */
    const val radiusFull = 999
}
