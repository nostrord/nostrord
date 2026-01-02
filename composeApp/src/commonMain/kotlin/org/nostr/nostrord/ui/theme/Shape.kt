package org.nostr.nostrord.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nostrord Design System - Shape Tokens
 *
 * Standardized corner radii and shapes.
 * Use subtle rounding - avoid the "rounded-corner obsession"
 * that plagues modern web apps.
 *
 * RULE: Most interactive elements use 4dp radius.
 * Only large containers use 8dp or more.
 */
object NostrordShapes {

    // ============================================
    // CORNER RADIUS VALUES
    // ============================================

    /** No rounding - sharp corners */
    val radiusNone: Dp = 0.dp

    /** Minimal rounding - buttons, inputs, list items */
    val radiusSmall: Dp = 4.dp

    /** Standard rounding - cards, panels, modals */
    val radiusMedium: Dp = 8.dp

    /** Large rounding - major containers, images */
    val radiusLarge: Dp = 12.dp

    /** Extra large rounding - splash screens, special cards */
    val radiusXLarge: Dp = 16.dp

    /** Full circle - avatars, status dots */
    val radiusFull: Dp = 999.dp

    // ============================================
    // SERVER ICON RADII (Special hover/active behavior)
    // ============================================

    /**
     * Server icon default state.
     * More rounded when inactive.
     */
    val serverIconDefault: Dp = 16.dp

    /**
     * Server icon hover/active state.
     * Less rounded when interactive.
     */
    val serverIconActive: Dp = 12.dp

    // ============================================
    // PRE-BUILT SHAPES
    // ============================================

    /** Sharp corners - dividers, indicators */
    val shapeNone: Shape = RoundedCornerShape(radiusNone)

    /** Small rounding - buttons, list items, inputs */
    val shapeSmall: Shape = RoundedCornerShape(radiusSmall)

    /** Standard rounding - cards, dialogs */
    val shapeMedium: Shape = RoundedCornerShape(radiusMedium)

    /** Large rounding - images, major containers */
    val shapeLarge: Shape = RoundedCornerShape(radiusLarge)

    /** Extra large rounding - special surfaces */
    val shapeXLarge: Shape = RoundedCornerShape(radiusXLarge)

    /** Circle - avatars, status indicators */
    val shapeCircle: Shape = CircleShape

    // ============================================
    // COMPONENT-SPECIFIC SHAPES
    // ============================================

    /** Input field shape */
    val inputShape: Shape = RoundedCornerShape(radiusMedium)

    /** Button shape */
    val buttonShape: Shape = RoundedCornerShape(radiusSmall)

    /** Small button shape */
    val buttonSmallShape: Shape = RoundedCornerShape(radiusSmall)

    /** Card shape */
    val cardShape: Shape = RoundedCornerShape(radiusMedium)

    /** Modal/dialog shape */
    val modalShape: Shape = RoundedCornerShape(radiusMedium)

    /** Tooltip shape */
    val tooltipShape: Shape = RoundedCornerShape(radiusSmall)

    /** Context menu shape */
    val menuShape: Shape = RoundedCornerShape(radiusSmall)

    /** Badge shape */
    val badgeShape: Shape = CircleShape

    /** Channel item hover shape */
    val channelItemShape: Shape = RoundedCornerShape(radiusSmall)

    /** Message hover actions shape */
    val messageActionsShape: Shape = RoundedCornerShape(radiusSmall)

    /** Bottom sheet shape (top corners only) */
    val bottomSheetShape: Shape = RoundedCornerShape(
        topStart = radiusLarge,
        topEnd = radiusLarge,
        bottomStart = radiusNone,
        bottomEnd = radiusNone
    )

    /** Image shape in messages */
    val imageShape: Shape = RoundedCornerShape(radiusMedium)

    /** Code block shape */
    val codeBlockShape: Shape = RoundedCornerShape(radiusSmall)

    /** Pill shape - tags, chips */
    val pillShape: Shape = RoundedCornerShape(percent = 50)

    // ============================================
    // SERVER ICON SHAPES (animated between these)
    // ============================================

    /** Server icon default shape */
    val serverIconDefaultShape: Shape = RoundedCornerShape(serverIconDefault)

    /** Server icon active shape */
    val serverIconActiveShape: Shape = RoundedCornerShape(serverIconActive)

    // ============================================
    // HELPER FUNCTIONS
    // ============================================

    /**
     * Creates a rounded corner shape with custom radius.
     * Use sparingly - prefer the predefined shapes.
     */
    fun custom(radius: Dp): Shape = RoundedCornerShape(radius)

    /**
     * Creates a shape with different top and bottom radii.
     * Useful for attached elements.
     */
    fun topRounded(radius: Dp = radiusMedium): Shape = RoundedCornerShape(
        topStart = radius,
        topEnd = radius,
        bottomStart = radiusNone,
        bottomEnd = radiusNone
    )

    /**
     * Creates a shape with only bottom corners rounded.
     */
    fun bottomRounded(radius: Dp = radiusMedium): Shape = RoundedCornerShape(
        topStart = radiusNone,
        topEnd = radiusNone,
        bottomStart = radius,
        bottomEnd = radius
    )

    /**
     * Creates a shape with only left corners rounded.
     */
    fun leftRounded(radius: Dp = radiusMedium): Shape = RoundedCornerShape(
        topStart = radius,
        topEnd = radiusNone,
        bottomStart = radius,
        bottomEnd = radiusNone
    )

    /**
     * Creates a shape with only right corners rounded.
     */
    fun rightRounded(radius: Dp = radiusMedium): Shape = RoundedCornerShape(
        topStart = radiusNone,
        topEnd = radius,
        bottomStart = radiusNone,
        bottomEnd = radius
    )
}
