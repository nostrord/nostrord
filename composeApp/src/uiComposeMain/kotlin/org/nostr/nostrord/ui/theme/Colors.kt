package org.nostr.nostrord.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nostrord Design System - Color Tokens (Compose)
 *
 * Dark theme color palette optimized for chat applications, semantically named for
 * their use case. These are thin Compose wrappers over [ColorTokens] in commonMain,
 * which is the single source of truth shared with the web UI. Edit a base color in
 * [ColorTokens], not here; only opacity variants and semantic aliases are defined here.
 */
object NostrordColors {
    // ============================================
    // BACKGROUND COLORS
    // ============================================

    /** Main message area background */
    val Background = Color(ColorTokens.Background)

    /** Server rail, channel header, top bars */
    val BackgroundDark = Color(ColorTokens.BackgroundDark)

    /** Sidebars (channel list, member list) */
    val Surface = Color(ColorTokens.Surface)

    /** Selected/active item background, input containers */
    val SurfaceVariant = Color(ColorTokens.SurfaceVariant)

    /** Text input fields */
    val InputBackground = Color(ColorTokens.InputBackground)

    /** Hover state background - slightly lighter than surface */
    val HoverBackground = Color(ColorTokens.HoverBackground)

    /** Message hover highlight */
    val MessageHover = Color(ColorTokens.MessageHover)

    // ============================================
    // PRIMARY / BRAND COLORS
    // ============================================

    /** Primary blurple - use ONLY for active states, buttons, links */
    val Primary = Color(ColorTokens.Primary)

    /** Primary hover/pressed state */
    val PrimaryVariant = Color(ColorTokens.PrimaryVariant)

    /** Primary with low opacity for subtle highlights */
    val PrimarySubtle = Color(ColorTokens.Primary).copy(alpha = 0.1f)

    // ============================================
    // SEMANTIC ACCENT COLORS
    // ============================================

    /** Online status, success states, join confirmation */
    val Success = Color(ColorTokens.Success)

    /** Errors, leave button, destructive actions */
    val Error = Color(ColorTokens.Error)

    /** Connecting status, pending states */
    val Warning = Color(ColorTokens.Warning)

    /** Alternative warning (darker) */
    val WarningOrange = Color(ColorTokens.WarningOrange)

    /** Boost/special features */
    val Pink = Color(ColorTokens.Pink)

    /** Soft error states */
    val LightRed = Color(ColorTokens.LightRed)

    /** Links, special content */
    val Teal = Color(ColorTokens.Teal)

    /** Highlights */
    val Mint = Color(ColorTokens.Mint)

    // ============================================
    // TEXT COLORS
    // ============================================

    /** Primary text - headers, active items, usernames */
    val TextPrimary = Color(ColorTokens.TextPrimary)

    /** Secondary text - deselected items, subtitles */
    val TextSecondary = Color(ColorTokens.TextSecondary)

    /** Muted text - timestamps, metadata, placeholders */
    val TextMuted = Color(ColorTokens.TextMuted)

    /** Message body text - optimized for reading */
    val TextContent = Color(ColorTokens.TextContent)

    /** Link text color */
    val TextLink = Color(ColorTokens.TextLink)

    /** Hashtag text color */
    val HashtagText = Color(ColorTokens.HashtagText)

    // ============================================
    // CODE COLORS
    // ============================================

    /** Code block background */
    val CodeBackground = Color(ColorTokens.CodeBackground)

    /** Code block text color */
    val CodeText = Color(ColorTokens.CodeText)

    /** Code language badge color */
    val CodeLanguageBadge = Color(ColorTokens.Primary)

    // ============================================
    // CHANNEL LIST SPECIFIC
    // ============================================

    /** Active/selected channel text */
    val ChannelActive = Color(ColorTokens.TextPrimary)

    /** Inactive/deselected channel text */
    val ChannelInactive = Color(ColorTokens.ChannelInactive)

    /** Unread channel text (bold white) */
    val ChannelUnread = Color(ColorTokens.ChannelUnread)

    /** Channel hover text */
    val ChannelHover = Color(ColorTokens.TextContent)

    // ============================================
    // MENTION COLORS
    // ============================================

    /** @mention background highlight */
    val MentionBackground = Color(ColorTokens.MentionAccent).copy(alpha = 0.1f)

    /** @mention text color */
    val MentionText = Color(ColorTokens.MentionText)

    /** @mention hover background */
    val MentionHoverBackground = Color(ColorTokens.MentionAccent).copy(alpha = 0.2f)

    // ============================================
    // STATUS COLORS
    // ============================================

    /** Online status indicator */
    val StatusOnline = Color(ColorTokens.Success)

    /** Idle status indicator */
    val StatusIdle = Color(ColorTokens.MentionAccent)

    /** Do not disturb status */
    val StatusDnd = Color(ColorTokens.Error)

    /** Offline/invisible status */
    val StatusOffline = Color(ColorTokens.StatusOffline)

    // ============================================
    // DIVIDERS & BORDERS
    // ============================================

    /** Standard divider color */
    val Divider = Color(ColorTokens.Divider)

    /** Subtle divider (lower contrast) */
    val DividerSubtle = Color(ColorTokens.Background)

    /** Focus ring / active border */
    val FocusRing = Primary

    // ============================================
    // UNREAD BADGE
    // ============================================

    /** Unread notification badge background */
    val BadgeBackground = Error

    /** Unread notification badge text */
    val BadgeText = Color(ColorTokens.TextPrimary)

    // ============================================
    // AVATAR FALLBACK COLORS
    // ============================================

    /** Palette for generating avatar colors from strings */
    val AvatarColors = ColorTokens.AvatarColors.map { Color(it) }
}
