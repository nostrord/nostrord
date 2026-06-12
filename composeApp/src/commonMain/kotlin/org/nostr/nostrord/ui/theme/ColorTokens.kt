package org.nostr.nostrord.ui.theme

import org.nostr.nostrord.settings.AppTheme

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
    // Backgrounds — Nostrord violet-charcoal ladder
    const val Background = 0xFF2B2A32L
    const val BackgroundDark = 0xFF1B1A20L
    const val Surface = 0xFF25242BL
    const val SurfaceVariant = 0xFF34323CL
    const val InputBackground = 0xFF31303AL
    const val InputHover = 0xFF3E3B49L
    const val HoverBackground = 0xFF302F37L
    const val MessageHover = 0xFF2E2D35L
    const val BackgroundFloating = 0xFF131217L

    // Primary / brand — nostr violet (white-on-brand ≈ 4.5:1)
    const val Primary = 0xFF7A5AF8L
    const val PrimaryVariant = 0xFF6847E0L

    // Semantic accents
    const val Success = 0xFF3DD68CL
    const val Error = 0xFFE5484DL
    const val Warning = 0xFFF5A524L
    const val WarningOrange = 0xFFE8590CL
    const val Pink = 0xFFD6409FL
    const val LightRed = 0xFFF2766BL
    const val Teal = 0xFF2DD4BFL
    const val Mint = 0xFF9AE6C8L

    // Text — violet-tinted whites/greys
    const val TextPrimary = 0xFFFFFFFFL
    const val TextSecondary = 0xFFA7A4B3L
    const val TextMuted = 0xFF757282L
    const val TextContent = 0xFFE2E1E6L
    const val TextLink = 0xFF5AB8FFL
    const val HashtagText = 0xFF9D86FAL

    // Code
    const val CodeBackground = 0xFF222129L
    const val CodeText = 0xFFE2E1E6L

    // Channel list
    const val ChannelInactive = 0xFF8B8896L
    const val ChannelUnread = 0xFFF5F4F7L

    // Mentions (MentionAccent is the base for the alpha bg variants + idle status)
    const val MentionText = 0xFFE3B45CL
    const val MentionAccent = 0xFFF5A524L

    // Status
    const val StatusOffline = 0xFF7A7787L

    // Dividers
    const val Divider = 0xFF34323CL

    // Full-page gradient corners (login/onboarding); Background sits in the middle
    const val PageGradientFrom = 0xFF2A2740L
    const val PageGradientTo = 0xFF16151EL

    /** Palette for generating avatar colors from strings. */
    val AvatarColors =
        listOf(
            0xFF7A5AF8L, // Nostr violet (brand)
            0xFF3DD68CL, // Green
            0xFFF5A524L, // Amber
            0xFFD6409FL, // Pink
            0xFFE5484DL, // Red
            0xFF2DD4BFL, // Teal
            0xFF4D9DE0L, // Blue
            0xFFE8590CL, // Orange
        )
}

/**
 * One resolved theme palette, field-per-field mirror of [ColorTokens]. The UIs read the
 * active palette at runtime instead of the constants directly, so switching the theme
 * swaps every color at once:
 *   - Compose: `NostrordColors` (uiComposeMain) holds the active palette in snapshot state.
 *   - Web: `applyColorTokens(palette)` re-injects the `--color-*` custom properties and
 *     updates `WebColors`.
 *
 * [ColorTokens] stays the dark source of truth; [DarkColorPalette] references it so the
 * two cannot drift. Avatar colors are identity colors and stay theme-independent.
 */
data class ColorPalette(
    val isDark: Boolean,
    val background: Long,
    val backgroundDark: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val inputBackground: Long,
    val inputHover: Long,
    val hoverBackground: Long,
    val messageHover: Long,
    val backgroundFloating: Long,
    val primary: Long,
    val primaryVariant: Long,
    val success: Long,
    val error: Long,
    val warning: Long,
    val warningOrange: Long,
    val pink: Long,
    val lightRed: Long,
    val teal: Long,
    val mint: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val textMuted: Long,
    val textContent: Long,
    val textLink: Long,
    val hashtagText: Long,
    val codeBackground: Long,
    val codeText: Long,
    val channelInactive: Long,
    val channelUnread: Long,
    val mentionText: Long,
    val mentionAccent: Long,
    val statusOffline: Long,
    val divider: Long,
    val pageGradientFrom: Long,
    val pageGradientTo: Long,
)

val DarkColorPalette =
    ColorPalette(
        isDark = true,
        background = ColorTokens.Background,
        backgroundDark = ColorTokens.BackgroundDark,
        surface = ColorTokens.Surface,
        surfaceVariant = ColorTokens.SurfaceVariant,
        inputBackground = ColorTokens.InputBackground,
        inputHover = ColorTokens.InputHover,
        hoverBackground = ColorTokens.HoverBackground,
        messageHover = ColorTokens.MessageHover,
        backgroundFloating = ColorTokens.BackgroundFloating,
        primary = ColorTokens.Primary,
        primaryVariant = ColorTokens.PrimaryVariant,
        success = ColorTokens.Success,
        error = ColorTokens.Error,
        warning = ColorTokens.Warning,
        warningOrange = ColorTokens.WarningOrange,
        pink = ColorTokens.Pink,
        lightRed = ColorTokens.LightRed,
        teal = ColorTokens.Teal,
        mint = ColorTokens.Mint,
        textPrimary = ColorTokens.TextPrimary,
        textSecondary = ColorTokens.TextSecondary,
        textMuted = ColorTokens.TextMuted,
        textContent = ColorTokens.TextContent,
        textLink = ColorTokens.TextLink,
        hashtagText = ColorTokens.HashtagText,
        codeBackground = ColorTokens.CodeBackground,
        codeText = ColorTokens.CodeText,
        channelInactive = ColorTokens.ChannelInactive,
        channelUnread = ColorTokens.ChannelUnread,
        mentionText = ColorTokens.MentionText,
        mentionAccent = ColorTokens.MentionAccent,
        statusOffline = ColorTokens.StatusOffline,
        divider = ColorTokens.Divider,
        pageGradientFrom = ColorTokens.PageGradientFrom,
        pageGradientTo = ColorTokens.PageGradientTo,
    )

/**
 * Light palette, ported from the design prototype's `[data-theme="light"]` tokens
 * (violet-tinted whites mirroring the dark ladder). Tokens the prototype does not
 * define (orange, teal, mint, code, channel, mention, status) are derived to stay
 * readable on white: text-ish tokens darken, amber follows the prototype's
 * dark-amber warning, backgrounds reuse the sidebar/input tints.
 */
val LightColorPalette =
    ColorPalette(
        isDark = false,
        background = 0xFFFFFFFFL, // bg-main
        backgroundDark = 0xFFE6E4EEL, // bg-rail
        surface = 0xFFEFEDF5L, // bg-sidebar
        surfaceVariant = 0xFFE8E6F0L, // bg-input
        inputBackground = 0xFFE8E6F0L,
        inputHover = 0xFFD4D0E2L, // bg-input-hover
        hoverBackground = 0xFFE3E1EBL, // bg-hover
        messageHover = 0xFFF5F4F9L, // bg-msg-hover
        backgroundFloating = 0xFFF4F3F8L, // bg-floating
        primary = ColorTokens.Primary, // brand is theme-invariant
        primaryVariant = ColorTokens.PrimaryVariant,
        success = 0xFF178A53L,
        error = 0xFFD03C45L,
        warning = 0xFF9A6700L, // dark amber: raw yellow is unreadable on white
        warningOrange = 0xFFC2410CL,
        pink = ColorTokens.Pink,
        lightRed = 0xFFD03C45L,
        teal = 0xFF0D9488L,
        mint = 0xFF2F9E73L,
        textPrimary = 0xFF1C1B22L,
        textSecondary = 0xFF514F5CL,
        textMuted = 0xFF7E7B8AL,
        textContent = 0xFF2D2C33L, // text-body
        textLink = 0xFF0B6EC9L,
        hashtagText = 0xFF6847E0L,
        codeBackground = 0xFFEFEDF5L,
        codeText = 0xFF2D2C33L,
        channelInactive = 0xFF6E6B7BL,
        channelUnread = 0xFF1C1B22L,
        mentionText = 0xFF9A6700L,
        mentionAccent = 0xFFF5A524L, // alpha-bg base + idle dot stay amber in both themes
        statusOffline = 0xFF8B8896L,
        divider = 0xFFE3E1EAL, // line
        pageGradientFrom = 0xFFE3DEF5L,
        pageGradientTo = 0xFFF0EEF8L,
    )

/** Resolve the user's theme preference to a concrete palette. */
fun paletteForTheme(
    theme: AppTheme,
    systemDark: Boolean,
): ColorPalette = when (theme) {
    AppTheme.DARK -> DarkColorPalette
    AppTheme.LIGHT -> LightColorPalette
    AppTheme.SYSTEM -> if (systemDark) DarkColorPalette else LightColorPalette
}

/** Convert an 0xAARRGGBB token to a CSS hex string `#rrggbb` (alpha dropped). */
fun argbToCssHex(argb: Long): String {
    fun h(v: Long) = v.toString(16).padStart(2, '0')
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#${h(r)}${h(g)}${h(b)}"
}
