package org.nostr.nostrord.web.theme

import kotlinx.browser.document
import org.nostr.nostrord.ui.theme.ColorTokens
import org.nostr.nostrord.ui.theme.DimenTokens
import org.nostr.nostrord.ui.theme.argbToCssHex
import org.w3c.dom.HTMLElement

/**
 * Inject the shared [ColorTokens] palette as `--color-*` CSS custom properties on
 * `:root` at startup, making commonMain authoritative for the web colors too.
 *
 * styles.css keeps the same values only as a pre-bundle cold-start fallback (the spinner
 * paints before this bundle runs). Once this runs the live values always come from the
 * tokens, so editing the palette in one place (ColorTokens) updates Compose and the web
 * together. The var names here must match the `--color-*` names used in styles.css.
 */
fun applyColorTokens() {
    val root = document.documentElement as? HTMLElement ?: return
    fun set(name: String, argb: Long) = root.style.setProperty(name, argbToCssHex(argb))

    set("--color-background", ColorTokens.Background)
    set("--color-background-dark", ColorTokens.BackgroundDark)
    set("--color-surface", ColorTokens.Surface)
    set("--color-surface-variant", ColorTokens.SurfaceVariant)
    set("--color-input", ColorTokens.InputBackground)
    set("--color-hover", ColorTokens.HoverBackground)
    set("--color-primary", ColorTokens.Primary)
    set("--color-primary-variant", ColorTokens.PrimaryVariant)
    set("--color-text-primary", ColorTokens.TextPrimary)
    set("--color-text-secondary", ColorTokens.TextSecondary)
    set("--color-text-content", ColorTokens.TextContent)
    set("--color-text-muted", ColorTokens.TextMuted)
    set("--color-text-link", ColorTokens.TextLink)
    set("--color-divider", ColorTokens.Divider)
    set("--color-error", ColorTokens.Error)
    set("--color-success", ColorTokens.Success)
    set("--color-warning-orange", ColorTokens.WarningOrange)
    set("--color-message-hover", ColorTokens.MessageHover)
    set("--color-mention", ColorTokens.MentionText)
}

/**
 * Inject the shared [DimenTokens] spacing and radius scale as `--space-*` / `--radius-*`
 * CSS custom properties on `:root`, so new web CSS can use the same scale Compose does.
 *
 * Existing CSS still uses ad-hoc literal px (see DimenTokens note); migrating those is a
 * separate visual-review task. This only publishes the canonical scale for new use.
 */
fun applyDimenTokens() {
    val root = document.documentElement as? HTMLElement ?: return
    fun set(name: String, px: Int) = root.style.setProperty(name, "${px}px")

    set("--space-xxs", DimenTokens.spaceXxs)
    set("--space-xs", DimenTokens.spaceXs)
    set("--space-sm", DimenTokens.spaceSm)
    set("--space-md", DimenTokens.spaceMd)
    set("--space-lg", DimenTokens.spaceLg)
    set("--space-xl", DimenTokens.spaceXl)
    set("--space-xxl", DimenTokens.spaceXxl)
    set("--space-xxxl", DimenTokens.spaceXxxl)

    set("--radius-none", DimenTokens.radiusNone)
    set("--radius-sm", DimenTokens.radiusSmall)
    set("--radius-md", DimenTokens.radiusMedium)
    set("--radius-lg", DimenTokens.radiusLarge)
    set("--radius-xl", DimenTokens.radiusXLarge)
    root.style.setProperty("--radius-full", "9999px")
}
