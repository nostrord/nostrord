package org.nostr.nostrord.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

/**
 * Nostrord Design System - Animation Tokens
 *
 * CORE PRINCIPLE: Animations must be fast and functional, never decorative.
 * - No animation should exceed 200ms
 * - Every animation must be interruptible
 * - Prefer opacity changes over movement
 *
 * Discord uses very subtle, fast animations. Match this feel.
 */
object NostrordAnimation {

    // ============================================
    // DURATION TOKENS (milliseconds)
    // ============================================

    /** Instant feedback - hover states, button press */
    const val instant: Int = 50

    /** Fast transitions - fade in/out, small movements */
    const val fast: Int = 100

    /** Standard transitions - panel slides, mode changes */
    const val standard: Int = 150

    /** Slow transitions - major view changes, overlays */
    const val slow: Int = 200

    /** MAXIMUM allowed duration - never exceed this */
    const val maxDuration: Int = 200

    // ============================================
    // SPECIFIC USE CASE DURATIONS
    // ============================================

    /** Hover background appearance */
    const val hoverEnter: Int = 100

    /** Hover background disappearance */
    const val hoverExit: Int = 50

    /** Message actions toolbar appear */
    const val actionsAppear: Int = 100

    /** Message actions toolbar disappear */
    const val actionsDisappear: Int = 50

    /** Popup/dialog appear */
    const val popupEnter: Int = 150

    /** Popup/dialog disappear */
    const val popupExit: Int = 100

    /** Drawer slide in */
    const val drawerEnter: Int = 150

    /** Drawer slide out */
    const val drawerExit: Int = 150

    /** Bottom sheet slide */
    const val bottomSheetSlide: Int = 150

    /** Tooltip appear delay (before animation starts) */
    const val tooltipDelay: Int = 500

    /** Hover actions delay (before actions toolbar appears) */
    const val hoverActionsDelay: Int = 50

    /** Tooltip fade in */
    const val tooltipEnter: Int = 100

    /** Tooltip fade out */
    const val tooltipExit: Int = 50

    /** Button press feedback */
    const val buttonPress: Int = 50

    /** Loading spinner rotation */
    const val loadingRotation: Int = 1000

    /** Shimmer effect cycle */
    const val shimmerCycle: Int = 1200

    /** Server icon shape morph (square to rounded) */
    const val iconMorph: Int = 150

    /** Unread badge appear */
    const val badgeAppear: Int = 100

    /** List item reorder */
    const val listReorder: Int = 150

    // ============================================
    // EASING CURVES
    // ============================================

    /** Standard easing - most transitions */
    val standardEasing: Easing = FastOutSlowInEasing

    /** Linear - opacity changes, color transitions */
    val linearEasing: Easing = LinearEasing

    /** Decelerate - elements entering view */
    val decelerateEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Accelerate - elements leaving view */
    val accelerateEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /** Emphasized - important state changes */
    val emphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    // ============================================
    // PRE-BUILT ANIMATION SPECS
    // ============================================

    /** Hover state enter animation */
    fun <T> hoverEnterSpec() = tween<T>(
        durationMillis = hoverEnter,
        easing = linearEasing
    )

    /** Hover state exit animation */
    fun <T> hoverExitSpec() = tween<T>(
        durationMillis = hoverExit,
        easing = linearEasing
    )

    /** Fade in animation */
    fun <T> fadeInSpec() = tween<T>(
        durationMillis = fast,
        easing = linearEasing
    )

    /** Fade out animation */
    fun <T> fadeOutSpec() = tween<T>(
        durationMillis = instant,
        easing = linearEasing
    )

    /** Standard transition */
    fun <T> standardSpec() = tween<T>(
        durationMillis = standard,
        easing = standardEasing
    )

    /** Panel slide animation */
    fun <T> panelSlideSpec() = tween<T>(
        durationMillis = drawerEnter,
        easing = decelerateEasing
    )

    /** Popup enter animation */
    fun <T> popupEnterSpec() = tween<T>(
        durationMillis = popupEnter,
        easing = decelerateEasing
    )

    /** Popup exit animation */
    fun <T> popupExitSpec() = tween<T>(
        durationMillis = popupExit,
        easing = accelerateEasing
    )

    /** List item content change */
    fun <T> contentChangeSpec() = tween<T>(
        durationMillis = fast,
        easing = linearEasing
    )

    /** Indicator position change */
    fun <T> indicatorSpec() = tween<T>(
        durationMillis = standard,
        easing = emphasizedEasing
    )

    /** Message actions appear (100ms fade in) */
    fun <T> actionsAppearSpec() = tween<T>(
        durationMillis = actionsAppear,
        easing = linearEasing
    )

    /** Message actions disappear (50ms fade out) */
    fun <T> actionsDisappearSpec() = tween<T>(
        durationMillis = actionsDisappear,
        easing = linearEasing
    )

    /** Context menu appear */
    fun <T> contextMenuEnterSpec() = tween<T>(
        durationMillis = popupEnter,
        easing = decelerateEasing
    )

    /** Context menu disappear */
    fun <T> contextMenuExitSpec() = tween<T>(
        durationMillis = popupExit,
        easing = accelerateEasing
    )
}
