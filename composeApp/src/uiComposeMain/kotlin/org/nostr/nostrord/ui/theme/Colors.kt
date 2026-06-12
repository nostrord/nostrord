package org.nostr.nostrord.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.nostr.nostrord.settings.AppTheme

/**
 * Nostrord Design System - Color Tokens (Compose)
 *
 * Semantically named colors for the chat UI, resolved from the active [ColorPalette]
 * (dark or light) in commonMain, the single source of truth shared with the web UI.
 * The palette lives in Compose snapshot state, so every composable that reads a color
 * recomposes when [apply] switches the theme. Edit base colors in [ColorTokens] /
 * [LightColorPalette], not here; only opacity variants and semantic aliases live here.
 */
object NostrordColors {
    private var palette by mutableStateOf(DarkColorPalette)

    /**
     * Install the palette for the user's theme preference. Called from the App root
     * during composition; writing an equal palette is a no-op, so calling it on every
     * recomposition is safe.
     */
    fun apply(
        theme: AppTheme,
        systemDark: Boolean,
    ) {
        palette = paletteForTheme(theme, systemDark)
    }

    /** True while the dark palette is active (drives Material light/dark scheme). */
    val IsDark: Boolean get() = palette.isDark

    // ============================================
    // BACKGROUND COLORS
    // ============================================

    /** Main message area background */
    val Background: Color get() = Color(palette.background)

    /** Server rail, channel header, top bars */
    val BackgroundDark: Color get() = Color(palette.backgroundDark)

    /** Sidebars (channel list, member list) */
    val Surface: Color get() = Color(palette.surface)

    /** Selected/active item background, input containers */
    val SurfaceVariant: Color get() = Color(palette.surfaceVariant)

    /** Text input fields */
    val InputBackground: Color get() = Color(palette.inputBackground)

    /** Hover state background - slightly lighter than surface */
    val HoverBackground: Color get() = Color(palette.hoverBackground)

    /** Message hover highlight */
    val MessageHover: Color get() = Color(palette.messageHover)

    /** Full-page gradient corners (login/onboarding); Background sits in the middle */
    val PageGradientFrom: Color get() = Color(palette.pageGradientFrom)
    val PageGradientTo: Color get() = Color(palette.pageGradientTo)

    // ============================================
    // PRIMARY / BRAND COLORS
    // ============================================

    /** Primary brand violet - use ONLY for active states, buttons, links */
    val Primary: Color get() = Color(palette.primary)

    /** Primary hover/pressed state */
    val PrimaryVariant: Color get() = Color(palette.primaryVariant)

    /** Primary with low opacity for subtle highlights */
    val PrimarySubtle: Color get() = Color(palette.primary).copy(alpha = 0.1f)

    // ============================================
    // SEMANTIC ACCENT COLORS
    // ============================================

    /** Online status, success states, join confirmation */
    val Success: Color get() = Color(palette.success)

    /** Errors, leave button, destructive actions */
    val Error: Color get() = Color(palette.error)

    /** Connecting status, pending states */
    val Warning: Color get() = Color(palette.warning)

    /** Alternative warning (darker) */
    val WarningOrange: Color get() = Color(palette.warningOrange)

    /** Boost/special features */
    val Pink: Color get() = Color(palette.pink)

    /** Soft error states */
    val LightRed: Color get() = Color(palette.lightRed)

    /** Links, special content */
    val Teal: Color get() = Color(palette.teal)

    /** Highlights */
    val Mint: Color get() = Color(palette.mint)

    // ============================================
    // TEXT COLORS
    // ============================================

    /** Primary text - headers, active items, usernames */
    val TextPrimary: Color get() = Color(palette.textPrimary)

    /** Secondary text - deselected items, subtitles */
    val TextSecondary: Color get() = Color(palette.textSecondary)

    /** Muted text - timestamps, metadata, placeholders */
    val TextMuted: Color get() = Color(palette.textMuted)

    /** Message body text - optimized for reading */
    val TextContent: Color get() = Color(palette.textContent)

    /** Link text color */
    val TextLink: Color get() = Color(palette.textLink)

    /** Hashtag text color */
    val HashtagText: Color get() = Color(palette.hashtagText)

    // ============================================
    // CODE COLORS
    // ============================================

    /** Code block background */
    val CodeBackground: Color get() = Color(palette.codeBackground)

    /** Code block text color */
    val CodeText: Color get() = Color(palette.codeText)

    /** Code language badge color */
    val CodeLanguageBadge: Color get() = Color(palette.primary)

    // ============================================
    // CHANNEL LIST SPECIFIC
    // ============================================

    /** Active/selected channel text */
    val ChannelActive: Color get() = Color(palette.textPrimary)

    /** Inactive/deselected channel text */
    val ChannelInactive: Color get() = Color(palette.channelInactive)

    /** Unread channel text (high contrast) */
    val ChannelUnread: Color get() = Color(palette.channelUnread)

    /** Channel hover text */
    val ChannelHover: Color get() = Color(palette.textContent)

    // ============================================
    // MENTION COLORS
    // ============================================

    /** @mention background highlight */
    val MentionBackground: Color get() = Color(palette.mentionAccent).copy(alpha = 0.1f)

    /** @mention text color */
    val MentionText: Color get() = Color(palette.mentionText)

    /** @mention hover background */
    val MentionHoverBackground: Color get() = Color(palette.mentionAccent).copy(alpha = 0.2f)

    // ============================================
    // STATUS COLORS
    // ============================================

    /** Online status indicator */
    val StatusOnline: Color get() = Color(palette.success)

    /** Idle status indicator */
    val StatusIdle: Color get() = Color(palette.mentionAccent)

    /** Do not disturb status */
    val StatusDnd: Color get() = Color(palette.error)

    /** Offline/invisible status */
    val StatusOffline: Color get() = Color(palette.statusOffline)

    // ============================================
    // DIVIDERS & BORDERS
    // ============================================

    /** Standard divider color */
    val Divider: Color get() = Color(palette.divider)

    /** Subtle divider (lower contrast) */
    val DividerSubtle: Color get() = Color(palette.background)

    /** Focus ring / active border */
    val FocusRing: Color get() = Primary

    // ============================================
    // UNREAD BADGE
    // ============================================

    /** Unread notification badge background */
    val BadgeBackground: Color get() = Error

    /** Unread notification badge text */
    val BadgeText: Color get() = Color(palette.textPrimary)

    // ============================================
    // AVATAR FALLBACK COLORS
    // ============================================

    /** Palette for generating avatar colors from strings (identity colors, theme-independent) */
    val AvatarColors = ColorTokens.AvatarColors.map { Color(it) }
}
