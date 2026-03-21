package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.isValidIconUrl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.buildRelayIconRequest
import org.nostr.nostrord.ui.util.generateColorFromString
import org.nostr.nostrord.ui.util.relayFallbackPainter

private data class SuggestedRelay(
    val url: String,
    val name: String,
    val description: String
)

private val suggestedRelays = listOf(
    SuggestedRelay("wss://groups.fiatjaf.com", "groups.fiatjaf.com", "A test relay for NIP-29 groups"),
    SuggestedRelay("wss://groups.0xchat.com", "0xchat Groups relay", "NIP-29 relay powering 0xchat group messaging"),
    SuggestedRelay("wss://relay.groups.nip29.com", "relay.groups.nip29.com", "Public NIP-29 groups relay"),
    SuggestedRelay("wss://groups.hzrd149.com", "groups.hzrd149.com", "NIP-29 group relay"),
    SuggestedRelay("wss://pyramid.fiatjaf.com", "pyramid.fiatjaf.com", "NIP-29 relay")
)

@Composable
fun AddRelayModal(
    connectedRelays: Set<String>,
    relayMetadata: Map<String, Nip11RelayInfo>,
    onSwitchRelay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Backdrop — dismiss on click
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            // Modal container
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(fraction = 0.92f)
                    .heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NostrordColors.Surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                var activeTab by remember { mutableIntStateOf(0) }

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add a Relay",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Pick a popular relay or enter a custom URL to connect.",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onDismiss)
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Tab bar using Material3 TabRow for correct underline behavior
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = Color.Transparent,
                    contentColor = NostrordColors.Primary,
                    divider = {
                        HorizontalDivider(color = NostrordColors.SurfaceVariant)
                    }
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        selectedContentColor = Color.White,
                        unselectedContentColor = NostrordColors.TextMuted
                    ) {
                        Text(
                            text = "Suggested",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        selectedContentColor = Color.White,
                        unselectedContentColor = NostrordColors.TextMuted
                    ) {
                        Text(
                            text = "Custom URL",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }

                // Tab content — weight(1f) ensures LazyColumn is constrained inside the modal
                Box(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        0 -> SuggestedTab(
                            connectedRelays = connectedRelays,
                            relayMetadata = relayMetadata,
                            onSelect = { url ->
                                onSwitchRelay(url)
                                onDismiss()
                            }
                        )
                        else -> CustomUrlTab(
                            onAdd = { url ->
                                onSwitchRelay(url)
                                onDismiss()
                            },
                            onCancel = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestedTab(
    connectedRelays: Set<String>,
    relayMetadata: Map<String, Nip11RelayInfo>,
    onSelect: (String) -> Unit
) {
    // Only show relays not yet connected (or mark them as added)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(suggestedRelays, key = { it.url }) { relay ->
            val meta = relayMetadata[relay.url]
            val displayName = meta?.name?.takeIf { it.isNotBlank() } ?: relay.name
            val description = meta?.description?.takeIf { it.isNotBlank() } ?: relay.description
            val isConnected = relay.url in connectedRelays

            SuggestedRelayCard(
                url = relay.url,
                name = displayName,
                description = description,
                iconUrl = meta?.icon,
                isConnected = isConnected,
                onAdd = { onSelect(relay.url) }
            )
        }
    }
}

@Composable
private fun SuggestedRelayCard(
    url: String,
    name: String,
    description: String,
    iconUrl: String?,
    isConnected: Boolean,
    onAdd: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val canAdd = !isConnected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered && canAdd) NostrordColors.SurfaceVariant else NostrordColors.BackgroundDark)
            .hoverable(interactionSource)
            .then(if (canAdd) Modifier.clickable(onClick = onAdd).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RelayCardIcon(url = url, name = name, iconUrl = iconUrl, size = 44.dp)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = url,
                color = NostrordColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        val btnBg = if (isConnected) NostrordColors.SurfaceVariant else NostrordColors.Primary
        val btnLabel = if (isConnected) "Added" else "Add"

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(btnBg)
                .then(if (canAdd) Modifier.clickable(onClick = onAdd).pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = btnLabel,
                color = if (isConnected) NostrordColors.TextMuted else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RelayCardIcon(url: String, name: String, iconUrl: String?, size: androidx.compose.ui.unit.Dp) {
    val context = LocalPlatformContext.current
    val fallbackPainter = if (iconUrl.isNullOrBlank()) relayFallbackPainter(url) else null
    val hasIcon = isValidIconUrl(iconUrl)
    var imageLoaded by remember(iconUrl) { mutableStateOf(false) }
    var retryCount by remember(iconUrl) { mutableIntStateOf(0) }
    var loadError by remember(iconUrl) { mutableStateOf(false) }
    LaunchedEffect(loadError, retryCount) {
        if (loadError && !imageLoaded) {
            val backoffMs = minOf(3_000L * (1 shl minOf(retryCount, 7)), 5 * 60_000L)
            println("[RelayCardIcon] will retry $iconUrl in ${backoffMs}ms (attempt ${retryCount + 2})")
            delay(backoffMs)
            retryCount++
            loadError = false
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(if (imageLoaded && hasIcon) NostrordColors.BackgroundDark else generateColorFromString(url)),
        contentAlignment = Alignment.Center
    ) {
        // Base layer: fallback shown until image overlays it
        if (fallbackPainter != null) {
            androidx.compose.foundation.Image(
                painter = fallbackPainter,
                contentDescription = name,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else if (!imageLoaded) {
            Text(
                text = name.take(1).uppercase(),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (hasIcon) {
            key(retryCount) {
                AsyncImage(
                    model = buildRelayIconRequest(iconUrl!!, context),
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                imageLoaded = true
                                loadError = false
                                println("[RelayCardIcon] loaded $iconUrl (attempt ${retryCount + 1})")
                            }
                            is AsyncImagePainter.State.Error -> {
                                imageLoaded = false
                                loadError = true
                                println("[RelayCardIcon] error $iconUrl attempt=${retryCount + 1}: ${state.result.throwable?.message}")
                            }
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomUrlTab(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var relayUrl by remember { mutableStateOf("") }
    val isValid = relayUrl.startsWith("wss://") && relayUrl.length > 6

    Column(modifier = Modifier.fillMaxSize()) {
        // Form
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "RELAY URL",
                color = NostrordColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.02.sp
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(NostrordColors.BackgroundDark)
                    .height(40.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (relayUrl.isEmpty()) {
                    Text(
                        text = "wss://relay.example.com",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(NostrordColors.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", color = NostrordColors.TextMuted, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isValid) NostrordColors.Primary else NostrordColors.SurfaceVariant)
                    .then(if (isValid) Modifier.clickable { onAdd(relayUrl.trim()) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Add Relay",
                    color = if (isValid) Color.White else NostrordColors.TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
