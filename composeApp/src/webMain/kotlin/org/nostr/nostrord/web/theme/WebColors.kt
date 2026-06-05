package org.nostr.nostrord.web.theme

import org.nostr.nostrord.ui.theme.ColorTokens
import org.nostr.nostrord.ui.theme.argbToCssHex

/**
 * Web color tokens as CSS hex strings, derived from the shared [ColorTokens] in
 * commonMain (the single source of truth shared with the Compose `NostrordColors`).
 *
 * Use these for inline / dynamic styling in React. Static styling uses the matching
 * CSS custom properties (`--color-*`), which are injected from the same tokens at
 * startup by [applyColorTokens].
 */
object WebColors {
    val Background = argbToCssHex(ColorTokens.Background)
    val BackgroundDark = argbToCssHex(ColorTokens.BackgroundDark)
    val Surface = argbToCssHex(ColorTokens.Surface)
    val SurfaceVariant = argbToCssHex(ColorTokens.SurfaceVariant)
    val Primary = argbToCssHex(ColorTokens.Primary)
    val PrimaryVariant = argbToCssHex(ColorTokens.PrimaryVariant)
    val TextPrimary = argbToCssHex(ColorTokens.TextPrimary)
    val TextSecondary = argbToCssHex(ColorTokens.TextSecondary)
    val TextContent = argbToCssHex(ColorTokens.TextContent)
    val TextMuted = argbToCssHex(ColorTokens.TextMuted)
    val TextLink = argbToCssHex(ColorTokens.TextLink)
    val Divider = argbToCssHex(ColorTokens.Divider)
    val Error = argbToCssHex(ColorTokens.Error)
    val Success = argbToCssHex(ColorTokens.Success)
}
