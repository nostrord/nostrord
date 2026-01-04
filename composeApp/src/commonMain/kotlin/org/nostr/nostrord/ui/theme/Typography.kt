package org.nostr.nostrord.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Nostrord Design System - Typography
 *
 * Type scale optimized for chat applications.
 * Key principle: Long reading sessions require generous line height.
 *
 * IMPORTANT: All styles use AppFonts.defaultFontFamily which is set at runtime.
 * On web, this is set to a custom FontFamily with CJK/Emoji/RTL support.
 * On other platforms, it defaults to FontFamily.SansSerif (system fonts).
 *
 * USAGE GUIDE:
 * - ServerHeader: Group/server names in headers
 * - SectionHeader: "CHANNELS", "MEMBERS", "ONLINE" labels
 * - ChannelName: #channel-name in sidebar
 * - ChannelNameUnread: #channel-name when has unread messages
 * - Username: Message author names
 * - MessageBody: Chat message content (CRITICAL: 157% line height)
 * - Timestamp: Message timestamps, metadata
 * - Badge: Unread counts, status badges
 * - InputPlaceholder: "Message #general" placeholder text
 * - Button: Button labels
 */
object NostrordTypography {

    // ============================================
    // HEADERS
    // ============================================

    /**
     * Server/group name in headers.
     * 15sp / SemiBold / 20sp line height
     */
    val ServerHeader: TextStyle
        get() = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Section headers like "CHANNELS", "MEMBERS", "ONLINE — 5"
     * 12sp / Bold / UPPERCASE / 0.08em letter spacing
     */
    val SectionHeader: TextStyle
        get() = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
            letterSpacing = 0.08.em,
            fontFamily = AppFonts.defaultFontFamily
            // Note: Apply .uppercase() to the text content
        )

    // ============================================
    // CHANNEL LIST
    // ============================================

    /**
     * Standard channel name in sidebar.
     * 15sp / Medium / 20sp line height
     */
    val ChannelName: TextStyle
        get() = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Unread channel name - bolder for emphasis.
     * 15sp / SemiBold / 20sp line height
     */
    val ChannelNameUnread: TextStyle
        get() = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Channel hash symbol (#).
     * 16sp / Bold
     */
    val ChannelHash: TextStyle
        get() = TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // MESSAGE CONTENT
    // ============================================

    /**
     * Message author username.
     * 14sp / SemiBold / 18sp line height
     */
    val Username: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * CRITICAL: Message body text.
     *
     * 14sp font with 22sp line height (157% ratio).
     * This generous line height is essential for readability
     * during long chat sessions. Do NOT reduce.
     */
    val MessageBody: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp, // 157% - DO NOT CHANGE
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Message timestamp.
     * 11sp / Regular / Tight line height
     */
    val Timestamp: TextStyle
        get() = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 13.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * System messages (joins, leaves, pins).
     * 14sp / Regular / Italic appearance via content
     */
    val SystemMessage: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // BADGES & LABELS
    // ============================================

    /**
     * Unread count badge.
     * 10sp / Bold / Tight
     */
    val Badge: TextStyle
        get() = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 10.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Status labels (Online, Offline, etc.).
     * 11sp / SemiBold
     */
    val StatusLabel: TextStyle
        get() = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 13.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Small labels and captions.
     * 12sp / Regular
     */
    val Caption: TextStyle
        get() = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Tiny text (member counts, etc.).
     * 11sp / Regular
     */
    val Tiny: TextStyle
        get() = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 13.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // INPUT & BUTTONS
    // ============================================

    /**
     * Text input content.
     * 14sp / Regular / Standard line height
     */
    val Input: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Input placeholder text.
     * 14sp / Regular
     */
    val InputPlaceholder: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Primary button labels.
     * 14sp / Medium
     */
    val Button: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Small button labels.
     * 12sp / Medium
     */
    val ButtonSmall: TextStyle
        get() = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // MEMBER LIST
    // ============================================

    /**
     * Member name in sidebar.
     * 14sp / Regular
     */
    val MemberName: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Member role/status subtitle.
     * 12sp / Regular
     */
    val MemberSubtitle: TextStyle
        get() = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 14.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // SPECIAL
    // ============================================

    /**
     * Code/monospace text in messages.
     * Uses platform default monospace font.
     */
    val Code: TextStyle
        get() = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.monospaceFontFamily
        )

    /**
     * Link text in messages.
     * Same as MessageBody but styled differently via color.
     */
    val Link: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Quoted/reply text preview.
     * 13sp / Regular
     */
    val Quote: TextStyle
        get() = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Avatar initials.
     * Size should be calculated as avatarSize * 0.4
     */
    val AvatarInitial: TextStyle
        get() = TextStyle(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    // ============================================
    // TOOLTIPS & POPUPS
    // ============================================

    /**
     * Tooltip text.
     * 12sp / SemiBold
     */
    val Tooltip: TextStyle
        get() = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )

    /**
     * Context menu item text.
     * 14sp / Regular
     */
    val MenuItem: TextStyle
        get() = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
            fontFamily = AppFonts.defaultFontFamily
        )
}
