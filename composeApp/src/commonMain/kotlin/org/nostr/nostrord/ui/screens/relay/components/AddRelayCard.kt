package org.nostr.nostrord.ui.screens.relay.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun AddRelayCard(isCompact: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = NostrordColors.Primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = NostrordColors.Primary.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Plus icon in circle
            Box(
                modifier = Modifier
                    .size(if (isCompact) 44.dp else 52.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = NostrordColors.Primary.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .background(NostrordColors.Primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(if (isCompact) 24.dp else 28.dp)
                )
            }

            Spacer(modifier = Modifier.width(if (isCompact) 12.dp else 16.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Add New Relay",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                if (!isCompact) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connect to more NIP-29 group relays to discover new communities",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Add button
            OutlinedButton(
                onClick = onClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NostrordColors.Primary
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(NostrordColors.Primary)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
