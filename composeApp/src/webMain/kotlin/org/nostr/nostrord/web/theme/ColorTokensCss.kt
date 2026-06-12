package org.nostr.nostrord.web.theme

import kotlinx.browser.document
import kotlinx.browser.window
import org.nostr.nostrord.settings.AppTheme
import org.nostr.nostrord.ui.theme.ColorPalette
import org.nostr.nostrord.ui.theme.DarkColorPalette
import org.nostr.nostrord.ui.theme.DimenTokens
import org.nostr.nostrord.ui.theme.argbToCssHex
import org.nostr.nostrord.ui.theme.paletteForTheme
import org.w3c.dom.HTMLElement

/** Current OS dark-mode preference (resolves AppTheme.SYSTEM on the web). */
fun systemPrefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches

/** Resolve and apply the palette for the user's theme preference. */
fun applyTheme(theme: AppTheme) = applyColorTokens(paletteForTheme(theme, systemPrefersDark()))

/**
 * Inject the given [ColorPalette] as `--color-*` CSS custom properties on `:root`,
 * making commonMain authoritative for the web colors too. Called at startup with the
 * persisted theme's palette and again on every theme switch; it also stamps
 * `data-theme` on `<html>` so theme-conditional CSS (`[data-theme="light"] ...`) and
 * `color-scheme` follow.
 *
 * styles.css keeps the dark values only as a pre-bundle cold-start fallback (the spinner
 * paints before this bundle runs). Once this runs the live values always come from the
 * palette, so editing a color in one place (ColorTokens / LightColorPalette) updates
 * Compose and the web together.
 *
 * DRIFT GUARD: this is a hand-maintained mirror with no compile-time check. The three
 * lists must stay in sync — a `--color-*` name here, its declaration in styles.css `:root`,
 * and the ColorPalette field. Adding a token without a `set(...)` line here leaves the web
 * on the stale styles.css fallback while Compose moves; renaming a var only here makes the
 * styles.css `var(--color-*)` references fall back. When you add/rename one, touch all three.
 */
fun applyColorTokens(palette: ColorPalette = DarkColorPalette) {
    WebColors.palette = palette
    val root = document.documentElement as? HTMLElement ?: return
    root.setAttribute("data-theme", if (palette.isDark) "dark" else "light")
    fun set(name: String, argb: Long) = root.style.setProperty(name, argbToCssHex(argb))

    set("--color-background", palette.background)
    set("--color-background-dark", palette.backgroundDark)
    set("--color-surface", palette.surface)
    set("--color-surface-variant", palette.surfaceVariant)
    set("--color-input", palette.inputBackground)
    set("--color-hover", palette.hoverBackground)
    set("--color-floating", palette.backgroundFloating)
    set("--color-primary", palette.primary)
    set("--color-primary-variant", palette.primaryVariant)
    set("--color-text-primary", palette.textPrimary)
    set("--color-text-secondary", palette.textSecondary)
    set("--color-text-content", palette.textContent)
    set("--color-text-muted", palette.textMuted)
    set("--color-text-link", palette.textLink)
    set("--color-divider", palette.divider)
    set("--color-error", palette.error)
    set("--color-success", palette.success)
    set("--color-warning", palette.warning)
    set("--color-warning-orange", palette.warningOrange)
    set("--color-message-hover", palette.messageHover)
    set("--color-mention", palette.mentionText)
    set("--page-grad-from", palette.pageGradientFrom)
    set("--page-grad-to", palette.pageGradientTo)
}

/**
 * Inject the shared [DimenTokens] spacing and radius scale as `--space-*` / `--radius-*`
 * CSS custom properties on `:root`, so new web CSS can use the same scale Compose does.
 *
 * Existing CSS still uses ad-hoc literal px (see DimenTokens note); migrating those is a
 * separate visual-review task. This only publishes the canonical scale for new use.
 *
 * NOTE: no styles.css rule consumes `var(--space-*)` / `var(--radius-*)` yet — this is
 * intentional forward-publish for that planned migration, not dead code. If that migration
 * is dropped, delete this function and its main.kt call.
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
