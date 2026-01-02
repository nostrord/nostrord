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
    val ServerHeader = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    /**
     * Section headers like "CHANNELS", "MEMBERS", "ONLINE — 5"
     * 12sp / Bold / UPPERCASE / 0.08em letter spacing
     */
    val SectionHeader = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em
        // Note: Apply .uppercase() to the text content
    )

    // ============================================
    // CHANNEL LIST
    // ============================================

    /**
     * Standard channel name in sidebar.
     * 15sp / Medium / 20sp line height
     */
    val ChannelName = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    /**
     * Unread channel name - bolder for emphasis.
     * 15sp / SemiBold / 20sp line height
     */
    val ChannelNameUnread = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    /**
     * Channel hash symbol (#).
     * 16sp / Bold
     */
    val ChannelHash = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    // ============================================
    // MESSAGE CONTENT
    // ============================================

    /**
     * Message author username.
     * 14sp / SemiBold / 18sp line height
     */
    val Username = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    /**
     * CRITICAL: Message body text.
     *
     * 14sp font with 22sp line height (157% ratio).
     * This generous line height is essential for readability
     * during long chat sessions. Do NOT reduce.
     */
    val MessageBody = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp, // 157% - DO NOT CHANGE
        letterSpacing = 0.sp
    )

    /**
     * Message timestamp.
     * 11sp / Regular / Tight line height
     */
    val Timestamp = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 13.sp,
        letterSpacing = 0.sp
    )

    /**
     * System messages (joins, leaves, pins).
     * 14sp / Regular / Italic appearance via content
     */
    val SystemMessage = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    // ============================================
    // BADGES & LABELS
    // ============================================

    /**
     * Unread count badge.
     * 10sp / Bold / Tight
     */
    val Badge = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 10.sp,
        letterSpacing = 0.sp
    )

    /**
     * Status labels (Online, Offline, etc.).
     * 11sp / SemiBold
     */
    val StatusLabel = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 13.sp,
        letterSpacing = 0.sp
    )

    /**
     * Small labels and captions.
     * 12sp / Regular
     */
    val Caption = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    /**
     * Tiny text (member counts, etc.).
     * 11sp / Regular
     */
    val Tiny = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 13.sp,
        letterSpacing = 0.sp
    )

    // ============================================
    // INPUT & BUTTONS
    // ============================================

    /**
     * Text input content.
     * 14sp / Regular / Standard line height
     */
    val Input = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    /**
     * Input placeholder text.
     * 14sp / Regular
     */
    val InputPlaceholder = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    /**
     * Primary button labels.
     * 14sp / Medium
     */
    val Button = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    /**
     * Small button labels.
     * 12sp / Medium
     */
    val ButtonSmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )

    // ============================================
    // MEMBER LIST
    // ============================================

    /**
     * Member name in sidebar.
     * 14sp / Regular
     */
    val MemberName = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    /**
     * Member role/status subtitle.
     * 12sp / Regular
     */
    val MemberSubtitle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )

    // ============================================
    // SPECIAL
    // ============================================

    /**
     * Code/monospace text in messages.
     * Uses platform default monospace font.
     */
    val Code = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
        // fontFamily = FontFamily.Monospace // Apply at usage site
    )

    /**
     * Link text in messages.
     * Same as MessageBody but styled differently via color.
     */
    val Link = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    )

    /**
     * Quoted/reply text preview.
     * 13sp / Regular
     */
    val Quote = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    /**
     * Avatar initials.
     * Size should be calculated as avatarSize * 0.4
     */
    val AvatarInitial = TextStyle(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    )

    // ============================================
    // TOOLTIPS & POPUPS
    // ============================================

    /**
     * Tooltip text.
     * 12sp / SemiBold
     */
    val Tooltip = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    /**
     * Context menu item text.
     * 14sp / Regular
     */
    val MenuItem = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )
}
