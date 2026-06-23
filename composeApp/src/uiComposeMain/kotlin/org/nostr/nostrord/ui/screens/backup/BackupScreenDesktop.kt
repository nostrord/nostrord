package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.cards.WarningCard
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun BackupScreenDesktop(vm: BackupViewModel) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = NostrordColors.WarningOrange,
                    modifier =
                    Modifier
                        .size(64.dp)
                        .padding(16.dp),
                )

                Text(
                    "Backup Your Keys",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(24.dp))

                WarningCard(isCompact = false)

                Spacer(modifier = Modifier.height(24.dp))

                BackupKeysSections(vm)

                Spacer(modifier = Modifier.height(24.dp))

                InfoCard(
                    title = "Security Tips",
                    titleColor = NostrordColors.Warning,
                    icon = Icons.Default.Lightbulb,
                    content = backupSecurityTips.joinToString("\n") { "• $it" },
                    isCompact = false,
                )
            }
        }

        VerticalScrollbarWrapper(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
