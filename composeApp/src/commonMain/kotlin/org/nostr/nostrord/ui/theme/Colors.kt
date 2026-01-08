package org.nostr.nostrord.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nostrord Design System - Color Tokens
 *
 * Dark theme color palette optimized for chat applications.
 * All colors are semantically named for their use case.
 */
object NostrordColors {
    // ============================================
    // BACKGROUND COLORS
    // ============================================

    /** Main message area background */
    val Background = Color(0xFF36393F)

    /** Server rail, channel header, top bars */
    val BackgroundDark = Color(0xFF202225)

    /** Sidebars (channel list, member list) */
    val Surface = Color(0xFF2F3136)

    /** Selected/active item background, input containers */
    val SurfaceVariant = Color(0xFF40444B)

    /** Text input fields */
    val InputBackground = Color(0xFF383A40)

    /** Hover state background - slightly lighter than surface */
    val HoverBackground = Color(0xFF34373C)

    /** Message hover highlight */
    val MessageHover = Color(0xFF32353B)

    // ============================================
    // PRIMARY / BRAND COLORS
    // ============================================

    /** Primary blurple - use ONLY for active states, buttons, links */
    val Primary = Color(0xFF5865F2)

    /** Primary hover/pressed state */
    val PrimaryVariant = Color(0xFF4752C4)

    /** Primary with low opacity for subtle highlights */
    val PrimarySubtle = Color(0xFF5865F2).copy(alpha = 0.1f)

    // ============================================
    // SEMANTIC ACCENT COLORS
    // ============================================

    /** Online status, success states, join confirmation */
    val Success = Color(0xFF57F287)

    /** Errors, leave button, destructive actions */
    val Error = Color(0xFFED4245)

    /** Connecting status, pending states */
    val Warning = Color(0xFFFEE75C)

    /** Alternative warning (darker) */
    val WarningOrange = Color(0xFFFFA500)

    /** Boost/special features */
    val Pink = Color(0xFFEB459E)

    /** Soft error states */
    val LightRed = Color(0xFFFF6B6B)

    /** Links, special content */
    val Teal = Color(0xFF4ECDC4)

    /** Highlights */
    val Mint = Color(0xFF95E1D3)

    // ============================================
    // TEXT COLORS
    // ============================================

    /** Primary text - headers, active items, usernames */
    val TextPrimary = Color.White

    /** Secondary text - deselected items, subtitles */
    val TextSecondary = Color(0xFF99AAB5)

    /** Muted text - timestamps, metadata, placeholders */
    val TextMuted = Color(0xFF72767D)

    /** Message body text - optimized for reading */
    val TextContent = Color(0xFFDCDDDE)

    /** Link text color */
    val TextLink = Color(0xFF00AFF4)

    /** Hashtag text color */
    val HashtagText = Color(0xFF7289DA)

    // ============================================
    // CODE COLORS
    // ============================================

    /** Code block background */
    val CodeBackground = Color(0xFF2B2D31)

    /** Code block text color */
    val CodeText = Color(0xFFE3E5E8)

    /** Code language badge color */
    val CodeLanguageBadge = Color(0xFF5865F2)

    // ============================================
    // CHANNEL LIST SPECIFIC
    // ============================================

    /** Active/selected channel text */
    val ChannelActive = Color.White

    /** Inactive/deselected channel text */
    val ChannelInactive = Color(0xFF8E9297)

    /** Unread channel text (bold white) */
    val ChannelUnread = Color(0xFFF6F6F7)

    /** Channel hover text */
    val ChannelHover = Color(0xFFDCDDDE)

    // ============================================
    // MENTION COLORS
    // ============================================

    /** @mention background highlight */
    val MentionBackground = Color(0xFFFAA81A).copy(alpha = 0.1f)

    /** @mention text color */
    val MentionText = Color(0xFFDEB655)

    /** @mention hover background */
    val MentionHoverBackground = Color(0xFFFAA81A).copy(alpha = 0.2f)

    // ============================================
    // STATUS COLORS
    // ============================================

    /** Online status indicator */
    val StatusOnline = Color(0xFF57F287)

    /** Idle status indicator */
    val StatusIdle = Color(0xFFFAA81A)

    /** Do not disturb status */
    val StatusDnd = Color(0xFFED4245)

    /** Offline/invisible status */
    val StatusOffline = Color(0xFF747F8D)

    // ============================================
    // DIVIDERS & BORDERS
    // ============================================

    /** Standard divider color */
    val Divider = Color(0xFF40444B)

    /** Subtle divider (lower contrast) */
    val DividerSubtle = Color(0xFF36393F)

    /** Focus ring / active border */
    val FocusRing = Primary

    // ============================================
    // UNREAD BADGE
    // ============================================

    /** Unread notification badge background */
    val BadgeBackground = Error

    /** Unread notification badge text */
    val BadgeText = Color.White

    // ============================================
    // AVATAR FALLBACK COLORS
    // ============================================

    /** Palette for generating avatar colors from strings */
    val AvatarColors = listOf(
        Color(0xFF5865F2), // Blurple
        Color(0xFF57F287), // Green
        Color(0xFFFEE75C), // Yellow
        Color(0xFFEB459E), // Fuchsia
        Color(0xFFED4245), // Red
        Color(0xFF9B59B6), // Purple
        Color(0xFF3498DB), // Blue
        Color(0xFFE67E22)  // Orange
    )
}
