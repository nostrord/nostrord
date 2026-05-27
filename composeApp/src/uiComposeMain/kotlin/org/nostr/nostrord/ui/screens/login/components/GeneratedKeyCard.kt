package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.rememberClipboardWriter

@Composable
fun GeneratedKeyCard(privateKey: String) {
    val copyToClipboard = rememberClipboardWriter()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.WarningOrange.copy(alpha = 0.1f),
    ) {
        // Center every child horizontally so the warning panel reads as a
        // single coherent block (matches the web's .genkey-card layout).
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = NostrordColors.WarningOrange,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SAVE YOUR PRIVATE KEY",
                    color = NostrordColors.WarningOrange,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This is a copy of your key, not your only copy. Save it somewhere safe.",
                color = NostrordColors.TextContent,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = NostrordShapes.shapeSmall,
                    color = NostrordColors.BackgroundDark,
                ) {
                    Text(
                        text = privateKey,
                        color = NostrordColors.Success,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                // Copy button mirrors the web .genkey-copy: passive icon
                // tile, click copies + swaps to a green check for 2s.
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            copyToClipboard(privateKey)
                            copied = true
                        }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy private key",
                        tint = if (copied) NostrordColors.Success else NostrordColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
