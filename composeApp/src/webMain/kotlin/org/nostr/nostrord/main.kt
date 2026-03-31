package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.browser.document
import androidx.compose.runtime.*
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
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.theme.AppFonts

/**
 * Parse URL query parameters for deep linking.
 * Supports: /?relay=groups.hzrd149.com&group=a45b2f
 *           /?relay=groups.hzrd149.com
 */
private fun parseDeepLinkFromUrl() {
    val search = kotlinx.browser.window.location.search
    if (search.isBlank()) return

    val params = search.removePrefix("?").split("&").associate { param ->
        val idx = param.indexOf("=")
        if (idx >= 0) param.substring(0, idx) to param.substring(idx + 1)
        else param to ""
    }

    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return
    val relayUrl = if ("://" in relay) relay else "wss://$relay"
    val groupId = params["group"]?.takeIf { it.isNotBlank() }

    val context = if (groupId != null) {
        ExternalLaunchContext.OpenGroup(groupId = groupId, groupName = null, relayUrl = relayUrl)
    } else {
        ExternalLaunchContext.OpenRelay(relayUrl)
    }
    StartupResolver.setExternalLaunchContext(context)
}

/**
 * Web entry point with tiered font loading for Canvas-based rendering.
 * Tier 1 (Latin ~560KB) gates render; emoji/CJK/RTL load in background.
 *
 * See: https://github.com/JetBrains/compose-multiplatform/issues/3051
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    parseDeepLinkFromUrl()
    ComposeViewport {
        WebAppWithFontPreloading()
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun WebAppWithFontPreloading() {
    // Tier 1: Latin only (~560KB) — gates render
    val notoSansRegular = preloadFont(Res.font.NotoSans_Regular).value
    val notoSansBold = preloadFont(Res.font.NotoSans_Bold).value
    val notoColorEmoji = preloadFont(Res.font.NotoColorEmoji).value

    var tier1Registered by remember { mutableStateOf(false) }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val tier1Ready = notoSansRegular != null && notoSansBold != null

    LaunchedEffect(notoSansRegular, notoSansBold) {
        if (notoSansRegular != null && notoSansBold != null) {
            fontFamilyResolver.preload(FontFamily(listOf(notoSansRegular, notoSansBold)))
            AppFonts.setDefaultFontFamily(FontFamily(listOf(notoSansRegular, notoSansBold)))
            tier1Registered = true
        }
    }

    // Emoji loads in parallel, registered when ready (doesn't gate render)
    LaunchedEffect(notoColorEmoji, tier1Registered) {
        if (tier1Registered && notoColorEmoji != null) {
            fontFamilyResolver.preload(FontFamily(listOf(notoColorEmoji)))
            AppFonts.setDefaultFontFamily(
                FontFamily(buildList {
                    notoSansRegular?.let { add(it) }
                    notoSansBold?.let { add(it) }
                    add(notoColorEmoji)
                })
            )
        }
    }

    // Tier 2/3: deferred via conditional composition
    val tier2Fonts = if (tier1Registered) Tier2Fonts() else null
    val tier3Fonts = if (tier2Fonts?.allLoaded == true) Tier3Fonts() else null

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

    if (tier1Ready && tier1Registered) {
        App()
        LaunchedEffect(Unit) {
            document.getElementById("composeApplication")
                ?.setAttribute("data-app-ready", "true")
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF1E1F22)))
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
