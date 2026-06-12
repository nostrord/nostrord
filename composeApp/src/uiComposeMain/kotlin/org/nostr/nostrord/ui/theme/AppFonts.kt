package org.nostr.nostrord.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import nostrord.composeapp.generated.resources.NotoColorEmoji
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.inter_bold
import nostrord.composeapp.generated.resources.inter_medium
import nostrord.composeapp.generated.resources.inter_regular
import nostrord.composeapp.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * Application font configuration for the Compose UIs (android/jvm/ios).
 *
 * The primary UI face is Inter (OFL), bundled as static TTFs in Compose Resources —
 * the same family the web self-hosts as InterVariable.woff2 — so typography matches
 * across all platforms. App() loads it via [rememberInterFontFamily] and installs it
 * with [setDefaultFontFamily] before any content renders; [NostrordTypography] reads
 * [defaultFontFamily] on every style access.
 */
object AppFonts {
    /**
     * Default font family used by Typography. Inter once App() installs it;
     * FontFamily.SansSerif only as the pre-install fallback.
     */
    var defaultFontFamily: FontFamily = FontFamily.SansSerif
        private set

    /**
     * Monospace font family for code blocks.
     */
    var monospaceFontFamily: FontFamily = FontFamily.Monospace
        private set

    /**
     * Set the default font family. NostrordTypography styles are computed on access,
     * so everything composed afterwards uses the new family.
     */
    fun setDefaultFontFamily(fontFamily: FontFamily) {
        defaultFontFamily = fontFamily
    }

    /**
     * Set the monospace font family.
     */
    fun setMonospaceFontFamily(fontFamily: FontFamily) {
        monospaceFontFamily = fontFamily
    }
}

/**
 * NotoColorEmoji FontFamily for rendering color emojis on desktop/web (Skia).
 * Must be called from @Composable context since it loads from Compose Resources.
 */
@Composable
fun rememberEmojiFontFamily(): FontFamily {
    val font = Font(Res.font.NotoColorEmoji)
    return remember(font) { FontFamily(font) }
}

/**
 * Inter (Regular/Medium/SemiBold/Bold) from Compose Resources. Italic is synthesized
 * by Skia; intermediate weights resolve to the nearest bundled face.
 */
@Composable
fun rememberInterFontFamily(): FontFamily {
    val regular = Font(Res.font.inter_regular, FontWeight.Normal)
    val medium = Font(Res.font.inter_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.inter_bold, FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) {
        FontFamily(regular, medium, semiBold, bold)
    }
}
