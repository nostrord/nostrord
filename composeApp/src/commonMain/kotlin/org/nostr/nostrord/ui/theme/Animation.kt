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
 * Use very subtle, fast animations for a polished feel.
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

    /** Hover actions delay (before actions toolbar appears) */
    const val hoverActionsDelay: Int = 50

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

    /** Standard transition */
    fun <T> standardSpec() = tween<T>(
        durationMillis = standard,
        easing = standardEasing
    )

    /** Indicator position change */
    fun <T> indicatorSpec() = tween<T>(
        durationMillis = standard,
        easing = emphasizedEasing
    )
}
