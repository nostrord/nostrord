package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic gradient fallback for user avatars (prototype gradientAvatar): a
 * diagonal duotone seeded by the pubkey, with a soft white sheen near the top. The
 * caller clips to the avatar shape.
 */
@Composable
fun UserGradientAvatar(
    seed: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val gradient = remember(seed) { AvatarGradients.user(seed) }
    Box(
        modifier =
        modifier.size(size).drawBehind {
            drawUserGradient(gradient)
        },
    )
}

/**
 * Deterministic gradient fallback for group avatars (prototype gradientGroupAvatar):
 * a conic swirl seeded by the group id, with the group's initial letter on top (the
 * prototype shows the mock emoji there). The caller clips to the avatar shape.
 */
@Composable
fun GroupGradientAvatar(
    seed: String,
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val gradient = remember(seed) { AvatarGradients.group(seed) }
    // Skip whitespace / invisible chars (some NIP-29 groups carry a leading space
    // or zero-width char in their name); fall through to the seed, then nothing.
    val letter =
        (
            name?.firstOrNull { !it.isWhitespace() }
                ?: seed.firstOrNull { !it.isWhitespace() }
            )?.uppercaseChar()?.toString()
    Box(
        modifier =
        modifier.size(size).drawBehind {
            drawGroupGradient(gradient)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter,
                color = Color.White,
                // Half the tile, like the prototype (fontSize = size * 0.5) and the
                // web's .avatar-letter (50cqh).
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // Trim the font's built-in leading and center the glyph in its line box, so the
                // letter sits dead-centre at small sizes (16dp) instead of riding high.
                lineHeight = (size.value * 0.5f).sp,
                style =
                TextStyle(
                    lineHeightStyle =
                    LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
    }
}

/**
 * Deterministic gradient fallback for relay avatars (prototype gradientRelayAvatar): a
 * three-stop diagonal band seeded by the relay URL, with the relay's initial letter on
 * top. Distinct from the user duotone and the group conic. The caller clips to the
 * avatar shape.
 */
@Composable
fun RelayGradientAvatar(
    seed: String,
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val gradient = remember(seed) { AvatarGradients.relay(seed) }
    val letter =
        (
            name?.firstOrNull { !it.isWhitespace() }
                ?: seed.firstOrNull { !it.isWhitespace() }
            )?.uppercaseChar()?.toString()
    Box(
        modifier =
        modifier.size(size).drawBehind {
            drawRelayGradient(gradient)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter,
                color = Color.White,
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Hsl.toColor(): Color = Color.hsl(hue.toFloat(), saturation / 100f, lightness / 100f)

/**
 * CSS linear-gradient + the sheen radial. The CSS angle is clockwise from "to top";
 * the gradient line runs through the centre with the standard CSS length
 * |w*sin a| + |h*cos a|, so the colour ramp matches the web pixel-for-pixel.
 */
private fun DrawScope.drawUserGradient(g: AvatarGradients.User) {
    val a = g.angleDeg * PI / 180.0
    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
    val half = (abs(size.width * sin(a)) + abs(size.height * cos(a))).toFloat() / 2f
    drawRect(
        brush =
        Brush.linearGradient(
            colors = listOf(g.start.toColor(), g.end.toColor()),
            start = center - dir * half,
            end = center + dir * half,
        ),
    )
    // radial-gradient(circle at sheenX% 12%): white 28% fading out at 42% of the
    // farthest-corner radius (the CSS default extent).
    val sheenCenter = Offset(size.width * g.sheenX / 100f, size.height * 0.12f)
    val radius =
        maxOf(
            hypot(sheenCenter.x, sheenCenter.y),
            hypot(size.width - sheenCenter.x, sheenCenter.y),
            hypot(sheenCenter.x, size.height - sheenCenter.y),
            hypot(size.width - sheenCenter.x, size.height - sheenCenter.y),
        )
    drawRect(
        brush =
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.28f),
            0.42f to Color.White.copy(alpha = 0f),
            center = sheenCenter,
            radius = radius,
        ),
    )
}

/**
 * CSS conic-gradient(from Xdeg, c1, c2, c3, c1). CSS starts at 12 o'clock while the
 * sweep brush starts at 3 o'clock, hence the -90 in the rotation; the rect is
 * inflated to the diagonal so the rotation leaves no uncovered corners.
 */
/**
 * CSS linear-gradient(angle, start 0%, mid 50%, end 100%). Same diagonal-line math as
 * the user gradient (CSS angle clockwise from "to top"), with a middle stop so the ramp
 * matches the web relay band pixel-for-pixel.
 */
private fun DrawScope.drawRelayGradient(g: AvatarGradients.Relay) {
    val a = g.angleDeg * PI / 180.0
    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
    val half = (abs(size.width * sin(a)) + abs(size.height * cos(a))).toFloat() / 2f
    drawRect(
        brush =
        Brush.linearGradient(
            colorStops =
            arrayOf(
                0f to g.start.toColor(),
                0.5f to g.mid.toColor(),
                1f to g.end.toColor(),
            ),
            start = center - dir * half,
            end = center + dir * half,
        ),
    )
}

private fun DrawScope.drawGroupGradient(g: AvatarGradients.Group) {
    val r = hypot(size.width, size.height) / 2f
    rotate(degrees = g.fromDeg - 90f) {
        drawRect(
            brush =
            Brush.sweepGradient(
                colors = listOf(g.c1.toColor(), g.c2.toColor(), g.c3.toColor(), g.c1.toColor()),
                center = center,
            ),
            topLeft = center - Offset(r, r),
            size = Size(2f * r, 2f * r),
        )
    }
}
