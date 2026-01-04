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
 * - Tier 2 (Common ~38MB): CJK + RTL - Loads in background
 * - Tier 3 (Rare ~2MB): Specialized scripts - Loads last
 *
 * This reduces perceived load time from ~50MB to ~11MB before app is visible.
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
 *
 * Tier 1 (Critical): Latin + Emoji (~11MB) - Must load before app renders
 * Tier 2 (Common): CJK + RTL (~38MB) - Load in background, update dynamically
 * Tier 3 (Rare): Thai, Cherokee, Symbols, Math (~2MB) - Load last
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun WebAppWithFontPreloading() {
    // ========== TIER 1: Critical fonts (Latin + Emoji) ==========
    // These must load before showing the app (~11MB)
    val notoSansRegular = preloadFont(Res.font.NotoSans_Regular).value
    val notoSansBold = preloadFont(Res.font.NotoSans_Bold).value
    val notoColorEmoji = preloadFont(Res.font.NotoColorEmoji).value

    // ========== TIER 2: Common fonts (CJK + RTL) ==========
    // Load in background after app is visible (~38MB)
    val notoSansJP = preloadFont(Res.font.NotoSansJP_Regular).value
    val notoSansSC = preloadFont(Res.font.NotoSansSC_Regular).value
    val notoSansKR = preloadFont(Res.font.NotoSansKR_Regular).value
    val notoSansArabic = preloadFont(Res.font.NotoSansArabic_Regular).value
    val notoSansHebrew = preloadFont(Res.font.NotoSansHebrew_Regular).value

    // ========== TIER 3: Rare fonts (specialized scripts) ==========
    // Load last (~2MB)
    val notoSansThai = preloadFont(Res.font.NotoSansThai_Regular).value
    val notoSansCherokee = preloadFont(Res.font.NotoSansCherokee_Regular).value
    val notoSansSymbols = preloadFont(Res.font.NotoSansSymbols_Regular).value
    val notoSansSymbols2 = preloadFont(Res.font.NotoSansSymbols2_Regular).value
    val notoSansMath = preloadFont(Res.font.NotoSansMath_Regular).value

    // Track initialization state
    var tier1Registered by remember { mutableStateOf(false) }

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
        }
    }

    // Progressively register Tier 2 fonts as they load
    LaunchedEffect(notoSansJP) {
        notoSansJP?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansSC) {
        notoSansSC?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansKR) {
        notoSansKR?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansArabic) {
        notoSansArabic?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansHebrew) {
        notoSansHebrew?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    // Progressively register Tier 3 fonts as they load
    LaunchedEffect(notoSansThai) {
        notoSansThai?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansCherokee) {
        notoSansCherokee?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansSymbols) {
        notoSansSymbols?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansSymbols2) {
        notoSansSymbols2?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
        }
    }

    LaunchedEffect(notoSansMath) {
        notoSansMath?.let {
            fontFamilyResolver.preload(FontFamily(listOf(it)))
            updateFontFamily(notoSansRegular, notoSansBold, notoColorEmoji,
                notoSansJP, notoSansSC, notoSansKR, notoSansArabic, notoSansHebrew,
                notoSansThai, notoSansCherokee, notoSansSymbols, notoSansSymbols2, notoSansMath)
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
 * Updates the combined FontFamily with all currently loaded fonts.
 * Called each time a new font finishes loading.
 */
private fun updateFontFamily(
    notoSansRegular: Font?,
    notoSansBold: Font?,
    notoColorEmoji: Font?,
    notoSansJP: Font?,
    notoSansSC: Font?,
    notoSansKR: Font?,
    notoSansArabic: Font?,
    notoSansHebrew: Font?,
    notoSansThai: Font?,
    notoSansCherokee: Font?,
    notoSansSymbols: Font?,
    notoSansSymbols2: Font?,
    notoSansMath: Font?
) {
    // Build list of all currently loaded fonts
    val loadedFonts = buildList {
        notoSansRegular?.let { add(it) }
        notoSansBold?.let { add(it) }
        notoColorEmoji?.let { add(it) }
        notoSansJP?.let { add(it) }
        notoSansSC?.let { add(it) }
        notoSansKR?.let { add(it) }
        notoSansArabic?.let { add(it) }
        notoSansHebrew?.let { add(it) }
        notoSansThai?.let { add(it) }
        notoSansCherokee?.let { add(it) }
        notoSansSymbols?.let { add(it) }
        notoSansSymbols2?.let { add(it) }
        notoSansMath?.let { add(it) }
    }

    if (loadedFonts.isNotEmpty()) {
        AppFonts.setDefaultFontFamily(FontFamily(loadedFonts))
    }
}
