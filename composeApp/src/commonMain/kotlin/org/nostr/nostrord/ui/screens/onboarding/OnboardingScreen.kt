package org.nostr.nostrord.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.nostrord_logo
import org.jetbrains.compose.resources.painterResource
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun OnboardingScreen(onAddRelay: () -> Unit, onAddRelayCustomUrl: () -> Unit = onAddRelay) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
        val isCompact = maxWidth < 912.dp

        val logoSize: Dp = if (isCompact) 64.dp else 88.dp
        val logoRadius: Dp = if (isCompact) 16.dp else 20.dp
        val titleSize: TextUnit = if (isCompact) 20.sp else 24.sp
        val descSize: TextUnit = if (isCompact) 14.sp else 15.sp
        val hPad: Dp = if (isCompact) 20.dp else 24.dp
        val vPad: Dp = if (isCompact) 24.dp else 40.dp

        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) maxWidth else 520.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = hPad, vertical = vPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(Res.drawable.nostrord_logo),
                contentDescription = "Nostrord",
                modifier = Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(logoRadius))
            )

            Spacer(Modifier.height(if (isCompact) 16.dp else 20.dp))

            Text(
                text = "Welcome to Nostrord",
                color = NostrordColors.TextPrimary,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Group messaging on Nostr. Connect to relays, join communities, and chat — open, decentralized, and without any central server.",
                color = NostrordColors.TextMuted,
                fontSize = descSize,
                lineHeight = descSize * 1.55,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp)
            )

            Spacer(Modifier.height(if (isCompact) 20.dp else 28.dp))

            // Steps — row on desktop, column on compact
            if (isCompact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OnboardingStep("1", "Add a Relay", "Connect to a Nostr relay that hosts groups",
                        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(), compact = true)
                    OnboardingStep("2", "Browse Groups", "Explore available groups on the relay",
                        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(), compact = true)
                    OnboardingStep("3", "Start Chatting", "Join groups and chat with the community",
                        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(), compact = true)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OnboardingStep("1", "Add a Relay", "Connect to a Nostr relay that hosts groups",
                        modifier = Modifier.weight(1f), compact = false)
                    OnboardingStep("2", "Browse Groups", "Explore available groups on the relay",
                        modifier = Modifier.weight(1f), compact = false)
                    OnboardingStep("3", "Start Chatting", "Join groups and chat with the community",
                        modifier = Modifier.weight(1f), compact = false)
                }
            }

            Spacer(Modifier.height(if (isCompact) 20.dp else 28.dp))

            OnboardingButton(
                text = "Add Your First Relay",
                primary = true,
                fullWidth = isCompact,
                onClick = onAddRelay
            )

            Spacer(Modifier.height(8.dp))

            OnboardingButton(
                text = "I already have a relay URL",
                primary = false,
                fullWidth = isCompact,
                onClick = onAddRelayCustomUrl
            )
        }
    }
    }
}

@Composable
private fun OnboardingStep(
    number: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (compact) {
        // Horizontal layout on mobile: number badge + text side by side
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(number, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = NostrordColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = NostrordColors.TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    } else {
        // Vertical layout on desktop
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(NostrordColors.BackgroundDark)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(number, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(title, color = NostrordColors.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, color = NostrordColors.TextMuted, fontSize = 12.sp,
                lineHeight = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OnboardingButton(
    text: String,
    primary: Boolean,
    fullWidth: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = if (primary) {
        if (isHovered) Color(0xFF4752C4) else NostrordColors.Primary
    } else {
        if (isHovered) NostrordColors.SurfaceVariant else NostrordColors.Surface
    }

    val buttonModifier = (if (fullWidth) Modifier.fillMaxWidth() else Modifier)
        .clip(RoundedCornerShape(6.dp))
        .background(bg)
        .hoverable(interactionSource)
        .clickable(onClick = onClick)
        .pointerHoverIcon(PointerIcon.Hand)
        .padding(horizontal = 28.dp, vertical = if (primary) 12.dp else 10.dp)

    Box(
        modifier = buttonModifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = NostrordColors.TextPrimary,
            fontSize = if (primary) 14.sp else 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
