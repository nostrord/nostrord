package org.nostr.nostrord.web.theme

/**
 * Web color tokens — hex mirror of [org.nostr.nostrord.ui.theme.NostrordColors], which
 * lives in `uiComposeMain` and is therefore invisible to the js target.
 *
 * Use these for inline / dynamic styling in React. Static styling uses the matching CSS
 * custom properties (`--color-*`) declared in `styles.css`.
 *
 * TODO(migration): centralize the palette as pure data in commonMain so the Compose
 * theme and this object share a single source of truth instead of duplicating hex.
 */
object WebColors {
    const val Background = "#36393f"
    const val BackgroundDark = "#202225"
    const val Surface = "#2f3136"
    const val SurfaceVariant = "#40444b"
    const val Primary = "#5865f2"
    const val PrimaryVariant = "#4752c4"
    const val TextPrimary = "#ffffff"
    const val TextSecondary = "#99aab5"
    const val TextContent = "#dcddde"
    const val TextMuted = "#72767d"
    const val TextLink = "#00aff4"
    const val Divider = "#40444b"
    const val Error = "#ed4245"
    const val Success = "#57f287"
}
