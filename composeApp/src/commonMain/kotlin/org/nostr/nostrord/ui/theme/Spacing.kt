package org.nostr.nostrord.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nostrord Design System - Spacing Scale
 *
 * Standardized spacing tokens based on 4dp base unit.
 * ALL spacing in the app should use these values.
 *
 * RULE: Never use arbitrary values like 6.dp, 14.dp, 18.dp.
 * If a design requires non-standard spacing, it's probably wrong.
 */
object Spacing {

    // ============================================
    // BASE SCALE (4dp increments)
    // ============================================

    /** 2dp - Minimal gaps, inline spacing */
    val xxs: Dp = 2.dp

    /** 4dp - Icon margins, tight gaps */
    val xs: Dp = 4.dp

    /** 8dp - Standard component internal padding */
    val sm: Dp = 8.dp

    /** 12dp - Inter-component gaps */
    val md: Dp = 12.dp

    /** 16dp - Section padding, standard margins */
    val lg: Dp = 16.dp

    /** 20dp - Large section breaks */
    val xl: Dp = 20.dp

    /** 24dp - Page margins (mobile), major sections */
    val xxl: Dp = 24.dp

    /** 32dp - Extra large gaps */
    val xxxl: Dp = 32.dp

    // ============================================
    // SEMANTIC SPACING
    // ============================================

    /**
     * Message list padding - horizontal space on message rows.
     * Discord uses 16dp on each side.
     */
    val messagePaddingHorizontal: Dp = 16.dp

    /**
     * Message avatar to content gap.
     * Discord uses 16dp between avatar and text.
     */
    val messageAvatarGap: Dp = 16.dp

    /**
     * Avatar column width (includes avatar + gap).
     * 40dp avatar + 16dp gap = 56dp, but we use 72dp total
     * to match Discord's visual alignment.
     */
    val avatarColumnWidth: Dp = 72.dp

    /**
     * Space between grouped messages (same author).
     * Discord uses very tight spacing here.
     */
    val messageGroupGap: Dp = 2.dp

    /**
     * Space above first message in a new group.
     */
    val messageGroupStart: Dp = 16.dp

    /**
     * Channel list item horizontal padding.
     */
    val channelItemPaddingH: Dp = 8.dp

    /**
     * Channel list item vertical padding.
     */
    val channelItemPaddingV: Dp = 6.dp

    /**
     * Sidebar internal padding.
     */
    val sidebarPadding: Dp = 8.dp

    /**
     * Space between sidebar sections.
     */
    val sectionGap: Dp = 16.dp

    /**
     * Input field padding.
     */
    val inputPadding: Dp = 12.dp

    /**
     * Card/panel internal padding.
     */
    val cardPadding: Dp = 12.dp

    /**
     * Button horizontal padding.
     */
    val buttonPaddingH: Dp = 16.dp

    /**
     * Button vertical padding.
     */
    val buttonPaddingV: Dp = 8.dp

    /**
     * Small button horizontal padding.
     */
    val buttonSmallPaddingH: Dp = 12.dp

    /**
     * Small button vertical padding.
     */
    val buttonSmallPaddingV: Dp = 4.dp

    // ============================================
    // FIXED DIMENSIONS
    // ============================================

    /**
     * Server rail width (vertical group icons).
     * Discord: 72dp
     */
    val serverRailWidth: Dp = 72.dp

    /**
     * Channel sidebar width.
     * Discord: 240dp
     */
    val channelSidebarWidth: Dp = 240.dp

    /**
     * Member sidebar width.
     * Discord: 240dp
     */
    val memberSidebarWidth: Dp = 240.dp

    /**
     * Server icon size in rail.
     */
    val serverIconSize: Dp = 48.dp

    /**
     * Server icon gap in rail.
     */
    val serverIconGap: Dp = 8.dp

    /**
     * Message avatar size.
     */
    val avatarSize: Dp = 40.dp

    /**
     * Small avatar size (member list, mentions).
     */
    val avatarSizeSmall: Dp = 32.dp

    /**
     * Tiny avatar size (inline, compact).
     */
    val avatarSizeTiny: Dp = 24.dp

    /**
     * Channel list item height.
     * Discord: 32dp
     */
    val channelItemHeight: Dp = 32.dp

    /**
     * Member list item height.
     */
    val memberItemHeight: Dp = 42.dp

    /**
     * Top app bar height.
     */
    val topBarHeight: Dp = 48.dp

    /**
     * Bottom navigation height.
     */
    val bottomNavHeight: Dp = 56.dp

    /**
     * Header height (group name bar).
     */
    val headerHeight: Dp = 48.dp

    /**
     * Minimum touch target size (accessibility).
     */
    val touchTargetMin: Dp = 48.dp

    /**
     * Message content max width.
     * Prevents lines from being too long on wide screens.
     */
    val messageMaxWidth: Dp = 640.dp

    /**
     * Content area max width for centering.
     */
    val contentMaxWidth: Dp = 1200.dp

    // ============================================
    // DIVIDERS
    // ============================================

    /** Standard divider thickness */
    val dividerThickness: Dp = 1.dp

    /** Server rail divider height */
    val railDividerHeight: Dp = 32.dp

    /** Server rail divider width */
    val railDividerWidth: Dp = 2.dp

    // ============================================
    // INDICATOR DIMENSIONS
    // ============================================

    /** Active server indicator width */
    val activeIndicatorWidth: Dp = 4.dp

    /** Active server indicator height (active) */
    val activeIndicatorHeight: Dp = 36.dp

    /** Hover indicator height */
    val hoverIndicatorHeight: Dp = 20.dp

    /** Unread dot size */
    val unreadDotSize: Dp = 8.dp

    /** Status indicator dot size */
    val statusDotSize: Dp = 12.dp

    /** Badge min size */
    val badgeSize: Dp = 16.dp

    /** Badge max size for larger numbers */
    val badgeSizeLarge: Dp = 20.dp

    // ============================================
    // ICON SIZES
    // ============================================

    /** Small icon size (12dp) */
    val iconSm: Dp = 12.dp

    /** Medium icon size (24dp) - standard nav icons */
    val iconMd: Dp = 24.dp

    /** Large icon size (32dp) */
    val iconLg: Dp = 32.dp
}
