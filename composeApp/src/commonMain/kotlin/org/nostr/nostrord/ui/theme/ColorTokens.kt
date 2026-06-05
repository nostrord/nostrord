package org.nostr.nostrord.ui.theme

/**
 * Single source of truth for the Nostrord color palette, as platform-agnostic ARGB
 * Long values (0xAARRGGBB). Lives in commonMain so every UI derives from the same
 * numbers instead of hand-mirroring hex:
 *   - Compose: [NostrordColors] (uiComposeMain) wraps each token in `Color(...)`.
 *   - Web: [org.nostr.nostrord.web.theme.WebColors] renders them as CSS hex via
 *     [argbToCssHex], and the same tokens are injected as `--color-*` custom
 *     properties at web startup (see applyColorTokens).
 *
 * Keep alpha at FF for base tokens; opacity variants are derived per platform (Compose
 * `.copy(alpha=)`, CSS `rgba()`). Edit a color here once and both UIs update together.
 */
object ColorTokens {
    // Backgrounds
    const val Background = 0xFF36393FL
    const val BackgroundDark = 0xFF202225L
    const val Surface = 0xFF2F3136L
    const val SurfaceVariant = 0xFF40444BL
    const val InputBackground = 0xFF383A40L
    const val HoverBackground = 0xFF34373CL
    const val MessageHover = 0xFF32353BL

    // Primary / brand
    const val Primary = 0xFF5865F2L
    const val PrimaryVariant = 0xFF4752C4L

    // Semantic accents
    const val Success = 0xFF57F287L
    const val Error = 0xFFED4245L
    const val Warning = 0xFFFEE75CL
    const val WarningOrange = 0xFFFFA500L
    const val Pink = 0xFFEB459EL
    const val LightRed = 0xFFFF6B6BL
    const val Teal = 0xFF4ECDC4L
    const val Mint = 0xFF95E1D3L

    // Text
    const val TextPrimary = 0xFFFFFFFFL
    const val TextSecondary = 0xFF99AAB5L
    const val TextMuted = 0xFF72767DL
    const val TextContent = 0xFFDCDDDEL
    const val TextLink = 0xFF00AFF4L
    const val HashtagText = 0xFF7289DAL

    // Code
    const val CodeBackground = 0xFF2B2D31L
    const val CodeText = 0xFFE3E5E8L

    // Channel list
    const val ChannelInactive = 0xFF8E9297L
    const val ChannelUnread = 0xFFF6F6F7L

    // Mentions (MentionAccent is the base for the alpha bg variants + idle status)
    const val MentionText = 0xFFDEB655L
    const val MentionAccent = 0xFFFAA81AL

    // Status
    const val StatusOffline = 0xFF747F8DL

    // Dividers
    const val Divider = 0xFF40444BL

    /** Palette for generating avatar colors from strings. */
    val AvatarColors =
        listOf(
            0xFF5865F2L, // Blurple
            0xFF57F287L, // Green
            0xFFFEE75CL, // Yellow
            0xFFEB459EL, // Fuchsia
            0xFFED4245L, // Red
            0xFF9B59B6L, // Purple
            0xFF3498DBL, // Blue
            0xFFE67E22L, // Orange
        )
}

/** Convert an 0xAARRGGBB token to a CSS hex string `#rrggbb` (alpha dropped). */
fun argbToCssHex(argb: Long): String {
    fun h(v: Long) = v.toString(16).padStart(2, '0')
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#${h(r)}${h(g)}${h(b)}"
}
