package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.cards.WarningCard
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreenMobile(vm: BackupViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Keys", color = NostrordColors.TextPrimary) },
                colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark,
                ),
            )
        },
        containerColor = NostrordColors.Background,
    ) { paddingValues ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = NostrordColors.WarningOrange,
                modifier =
                Modifier
                    .size(48.dp)
                    .padding(8.dp),
            )

            Text(
                "Backup Your Keys",
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            WarningCard(isCompact = true)

            Spacer(modifier = Modifier.height(16.dp))

            BackupKeysSections(vm)

            Spacer(modifier = Modifier.height(16.dp))

            InfoCard(
                title = "Security Tips",
                titleColor = NostrordColors.Warning,
                icon = Icons.Default.Lightbulb,
                content = backupSecurityTips.joinToString("\n") { "• $it" },
                isCompact = true,
            )
        }
    }
}
