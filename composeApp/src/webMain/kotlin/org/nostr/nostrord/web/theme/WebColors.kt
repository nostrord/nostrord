package org.nostr.nostrord.web.theme

import org.nostr.nostrord.ui.theme.ColorPalette
import org.nostr.nostrord.ui.theme.DarkColorPalette
import org.nostr.nostrord.ui.theme.argbToCssHex

/**
 * Web color tokens as CSS hex strings, derived from the active [ColorPalette] in
 * commonMain (the single source of truth shared with the Compose `NostrordColors`).
 * [applyColorTokens] switches the palette when the theme changes; the theme change
 * re-renders the React tree, so inline usages pick up the new values.
 *
 * Use these for inline / dynamic styling in React. Static styling uses the matching
 * CSS custom properties (`--color-*`), which are injected from the same palette by
 * [applyColorTokens].
 */
object WebColors {
    /** Active palette; installed by [applyColorTokens]. */
    var palette: ColorPalette = DarkColorPalette
        internal set

    val Background get() = argbToCssHex(palette.background)
    val BackgroundDark get() = argbToCssHex(palette.backgroundDark)
    val Surface get() = argbToCssHex(palette.surface)
    val SurfaceVariant get() = argbToCssHex(palette.surfaceVariant)
    val Primary get() = argbToCssHex(palette.primary)
    val PrimaryVariant get() = argbToCssHex(palette.primaryVariant)
    val TextPrimary get() = argbToCssHex(palette.textPrimary)
    val TextSecondary get() = argbToCssHex(palette.textSecondary)
    val TextContent get() = argbToCssHex(palette.textContent)
    val TextMuted get() = argbToCssHex(palette.textMuted)
    val TextLink get() = argbToCssHex(palette.textLink)
    val Divider get() = argbToCssHex(palette.divider)
    val Error get() = argbToCssHex(palette.error)
    val Success get() = argbToCssHex(palette.success)
}
