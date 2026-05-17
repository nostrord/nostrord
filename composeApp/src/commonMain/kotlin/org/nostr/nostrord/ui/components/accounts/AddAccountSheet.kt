package org.nostr.nostrord.ui.components.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.ExtensionLoginTab
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab
import org.nostr.nostrord.ui.theme.NostrordColors

private val DESKTOP_WIDTH_THRESHOLD = 912.dp
private val DESKTOP_RAIL_WIDTH = 72.dp
private val DESKTOP_SHEET_WIDTH = 420.dp

private sealed class AddStep {
    object PickMethod : AddStep()
    object PrivateKey : AddStep()
    object Bunker : AddStep()
    object Extension : AddStep()
}

/**
 * Sheet that adds a new signed-in account. Reuses [PrivateKeyLoginTab] /
 * [BunkerLoginTab] / [ExtensionLoginTab] for the credential input. A
 * successful login swaps the active account via the existing warm-swap
 * path in [org.nostr.nostrord.network.NostrRepository.finishLoginInit].
 *
 * Desktop (wide windows): popup anchored above the rail avatar.
 * Mobile / narrow: modal bottom sheet.
 */
@Composable
fun AddAccountSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdded: (displayLabel: String) -> Unit,
) {
    if (!visible) return

    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val containerWidthDp = with(density) { containerWidthPx.toDp() }
    val isDesktop = containerWidthDp >= DESKTOP_WIDTH_THRESHOLD

    val accounts by AppModule.accountStore.accounts.collectAsState()
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val activeAccount = accounts.firstOrNull { it.id == activeId }
    val activeDisplayName = activeAccount?.let { acc ->
        userMetadata[acc.pubkey]?.displayName?.takeIf { it.isNotBlank() }
            ?: userMetadata[acc.pubkey]?.name?.takeIf { it.isNotBlank() }
            ?: acc.label
    }
    val scope = rememberCoroutineScope()

    var step: AddStep by remember { mutableStateOf<AddStep>(AddStep.PickMethod) }
    var isBusy by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    val notifyAdded: () -> Unit = {
        // After the warm-swap finishes, the active pubkey is the new one.
        // Look its label up from the freshly-emitted accountStore so the
        // snackbar prefers the user's profile name when available.
        val newActive = AppModule.accountStore.active
        val newMeta = newActive?.pubkey?.let { userMetadata[it] }
        val label = newMeta?.displayName?.takeIf { it.isNotBlank() }
            ?: newMeta?.name?.takeIf { it.isNotBlank() }
            ?: newActive?.label
            ?: "account"
        onAdded(label)
    }

    val onMethodSuccess: () -> Unit = { notifyAdded() }

    val content: @Composable ColumnScope.() -> Unit = {
        StepHeader(
            title = when (step) {
                AddStep.PickMethod -> "Add account"
                AddStep.PrivateKey -> "Private key"
                AddStep.Bunker -> "Bunker"
                AddStep.Extension -> "Browser extension"
            },
            showBack = step !is AddStep.PickMethod,
            onBack = { step = AddStep.PickMethod; pendingError = null },
            onClose = onDismiss,
        )
        HorizontalDivider(color = NostrordColors.BackgroundDark)

        pendingError?.let { msg ->
            Text(
                msg,
                color = NostrordColors.Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        when (step) {
            AddStep.PickMethod -> PickMethodContent(
                activeDisplayName = activeDisplayName,
                isBusy = isBusy,
                hasExtension = Nip07.isAvailable(),
                onPickPrivateKey = { step = AddStep.PrivateKey },
                onPickBunker = { step = AddStep.Bunker },
                onPickExtension = { step = AddStep.Extension },
            )
            AddStep.PrivateKey -> Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PrivateKeyLoginTab(onLoginSuccess = onMethodSuccess)
            }
            AddStep.Bunker -> Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                BunkerLoginTab(onLoginSuccess = onMethodSuccess)
            }
            AddStep.Extension -> Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ExtensionLoginTab(onLoginSuccess = onMethodSuccess)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (isDesktop) {
        DesktopAddAccountSheet(onDismiss = onDismiss, content = content)
    } else {
        MobileAddAccountSheet(onDismiss = onDismiss, content = content)
    }
}

@Composable
private fun DesktopAddAccountSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val offsetX = with(density) { DESKTOP_RAIL_WIDTH.roundToPx() }
    val offsetY = with(density) { (-12).dp.roundToPx() }
    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(offsetX, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = NostrordColors.Surface,
            shadowElevation = 12.dp,
            tonalElevation = 4.dp,
            modifier = Modifier.widthIn(min = DESKTOP_SHEET_WIDTH, max = 460.dp),
        ) {
            Column(content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAddAccountSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NostrordColors.Surface,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun StepHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        } else if (title == "Add account") {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

@Composable
private fun PickMethodContent(
    activeDisplayName: String?,
    isBusy: Boolean,
    hasExtension: Boolean,
    onPickPrivateKey: () -> Unit,
    onPickBunker: () -> Unit,
    onPickExtension: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (activeDisplayName != null) {
            Text(
                "You'll keep $activeDisplayName signed in.",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
        MethodRow(icon = Icons.Default.Key, label = "Private key", enabled = !isBusy, onClick = onPickPrivateKey)
        MethodRow(icon = Icons.Default.Shield, label = "Bunker (NIP-46)", enabled = !isBusy, onClick = onPickBunker)
        if (hasExtension) {
            MethodRow(icon = Icons.Default.Extension, label = "Browser extension", enabled = !isBusy, onClick = onPickExtension)
        }
    }
}

@Composable
private fun MethodRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val containerColor = if (highlight) NostrordColors.Primary.copy(alpha = 0.12f)
    else NostrordColors.BackgroundDark
    val labelColor = if (highlight) NostrordColors.Primary else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(containerColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = labelColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (!highlight) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
