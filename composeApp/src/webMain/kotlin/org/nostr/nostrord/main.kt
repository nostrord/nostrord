package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.ComposeViewport
import nostrord.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont
import org.nostr.nostrord.ui.theme.AppFonts

/**
 * Web entry point with PROGRESSIVE font loading for Canvas-based rendering.
 *
 * OPTIMIZATION: Uses tiered loading to show app faster:
 * - Tier 1 (Critical ~11MB): Latin + Emoji - App becomes usable immediately
 * - Tier 2 (Common ~38MB): CJK + RTL - Loads in background AFTER Tier 1
 * - Tier 3 (Rare ~2MB): Specialized scripts - Loads last
 *
 * This reduces perceived load time from ~50MB to ~11MB before app is visible.
 * IMPORTANT: Tier 2/3 fonts only start loading AFTER Tier 1 completes to prevent
 * mobile browsers from freezing due to too many concurrent downloads.
 *
 * See: https://github.com/JetBrains/compose-multiplatform/issues/3051
 * See: https://github.com/JetBrains/compose-multiplatform/issues/3967
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        WebAppWithFontPreloading()
    }
}

/**
 * Progressive font loading with tiered priority.
 * Uses conditional composition to truly defer Tier 2/3 loading.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun WebAppWithFontPreloading() {
    // ========== TIER 1: Critical fonts (Latin + Emoji) ==========
    // These must load before showing the app (~11MB)
    val notoSansRegular = preloadFont(Res.font.NotoSans_Regular).value
    val notoSansBold = preloadFont(Res.font.NotoSans_Bold).value
    val notoColorEmoji = preloadFont(Res.font.NotoColorEmoji).value

    // Track initialization state
    var tier1Registered by remember { mutableStateOf(false) }

    // Track which tier we're loading (for sequential loading)
    var currentTier by remember { mutableIntStateOf(1) }

    val fontFamilyResolver = LocalFontFamilyResolver.current

    // Check if Tier 1 (critical) fonts are ready
    val tier1Ready = notoSansRegular != null && notoSansBold != null && notoColorEmoji != null

    // Register Tier 1 fonts and show app immediately
    LaunchedEffect(notoSansRegular, notoSansBold, notoColorEmoji) {
        if (notoSansRegular != null && notoSansBold != null && notoColorEmoji != null) {
            // Register critical fonts first
            fontFamilyResolver.preload(FontFamily(listOf(notoSansRegular, notoSansBold)))
            fontFamilyResolver.preload(FontFamily(listOf(notoColorEmoji)))

            // Set initial font family with just Tier 1
            AppFonts.setDefaultFontFamily(
                FontFamily(listOf(notoSansRegular, notoSansBold, notoColorEmoji))
            )
            tier1Registered = true
            currentTier = 2
        }
    }

    // ========== DEFERRED LOADING: Tier 2 & 3 ==========
    // Only compose (and thus start loading) when previous tier completes
    // This uses conditional composition to truly defer the preloadFont calls

    // Tier 2 fonts - only start loading when tier1Registered is true
    val tier2Fonts = if (tier1Registered) {
        Tier2Fonts()
    } else {
        null
    }

    // Tier 3 fonts - only start loading when Tier 2 is complete
    val tier3Fonts = if (tier2Fonts?.allLoaded == true) {
        Tier3Fonts()
    } else {
        null
    }

    // Register Tier 2 fonts as they load
    LaunchedEffect(tier2Fonts) {
        tier2Fonts?.let { fonts ->
            val loadedFonts = buildList {
                notoSansRegular?.let { add(it) }
                notoSansBold?.let { add(it) }
                notoColorEmoji?.let { add(it) }
                fonts.jp?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.sc?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.kr?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.arabic?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.hebrew?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
            }
            if (loadedFonts.isNotEmpty()) {
                AppFonts.setDefaultFontFamily(FontFamily(loadedFonts))
            }
        }
    }

    // Register Tier 3 fonts as they load
    LaunchedEffect(tier3Fonts) {
        tier3Fonts?.let { fonts ->
            val loadedFonts = buildList {
                notoSansRegular?.let { add(it) }
                notoSansBold?.let { add(it) }
                notoColorEmoji?.let { add(it) }
                tier2Fonts?.jp?.let { add(it) }
                tier2Fonts?.sc?.let { add(it) }
                tier2Fonts?.kr?.let { add(it) }
                tier2Fonts?.arabic?.let { add(it) }
                tier2Fonts?.hebrew?.let { add(it) }
                fonts.thai?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.cherokee?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.symbols?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.symbols2?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
                fonts.math?.let { fontFamilyResolver.preload(FontFamily(listOf(it))); add(it) }
            }
            if (loadedFonts.isNotEmpty()) {
                AppFonts.setDefaultFontFamily(FontFamily(loadedFonts))
            }
        }
    }

    // Show app once Tier 1 is ready (instead of waiting for all ~50MB)
    if (tier1Ready && tier1Registered) {
        App()
    } else {
        // Show loading indicator only for critical fonts
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1F22)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF5865F2))
        }
    }
}

/**
 * Tier 2 fonts container - CJK + RTL (~38MB)
 * By being a separate composable, these fonts only start loading when this is composed.
 */
private data class Tier2FontState(
    val jp: Font?,
    val sc: Font?,
    val kr: Font?,
    val arabic: Font?,
    val hebrew: Font?
) {
    val allLoaded: Boolean
        get() = jp != null && sc != null && kr != null && arabic != null && hebrew != null
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun Tier2Fonts(): Tier2FontState {
    return Tier2FontState(
        jp = preloadFont(Res.font.NotoSansJP_Regular).value,
        sc = preloadFont(Res.font.NotoSansSC_Regular).value,
        kr = preloadFont(Res.font.NotoSansKR_Regular).value,
        arabic = preloadFont(Res.font.NotoSansArabic_Regular).value,
        hebrew = preloadFont(Res.font.NotoSansHebrew_Regular).value
    )
}

/**
 * Tier 3 fonts container - specialized scripts (~2MB)
 * By being a separate composable, these fonts only start loading when this is composed.
 */
private data class Tier3FontState(
    val thai: Font?,
    val cherokee: Font?,
    val symbols: Font?,
    val symbols2: Font?,
    val math: Font?
) {
    val allLoaded: Boolean
        get() = thai != null && cherokee != null && symbols != null && symbols2 != null && math != null
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun Tier3Fonts(): Tier3FontState {
    return Tier3FontState(
        thai = preloadFont(Res.font.NotoSansThai_Regular).value,
        cherokee = preloadFont(Res.font.NotoSansCherokee_Regular).value,
        symbols = preloadFont(Res.font.NotoSansSymbols_Regular).value,
        symbols2 = preloadFont(Res.font.NotoSansSymbols2_Regular).value,
        math = preloadFont(Res.font.NotoSansMath_Regular).value
    )
}
