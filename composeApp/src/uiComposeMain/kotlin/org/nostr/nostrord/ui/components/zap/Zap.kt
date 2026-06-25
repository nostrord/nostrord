package org.nostr.nostrord.ui.components.zap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.ui.components.QrCode
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.shortNpub

/**
 * App-wide entry point for opening the zap modal from anywhere (message context menu,
 * zap badge, profile button) without threading modal state through every screen.
 * A single [ZapModalHost] placed at the app root renders whatever target is active.
 */
object ZapController {
    data class Target(val recipientPubkey: String, val eventId: String?)

    private val _active = MutableStateFlow<Target?>(null)
    val active: StateFlow<Target?> = _active.asStateFlow()

    /** Open the zap modal for [recipientPubkey]; [eventId] is the zapped message, or null for a profile zap. */
    fun request(recipientPubkey: String, eventId: String? = null) {
        _active.value = Target(recipientPubkey, eventId)
    }

    fun dismiss() {
        _active.value = null
    }
}

/** Place once at the app root. Renders the zap modal whenever [ZapController] has an active target. */
@Composable
fun ZapModalHost() {
    val target by ZapController.active.collectAsState()
    target?.let {
        ZapModal(
            recipientPubkey = it.recipientPubkey,
            eventId = it.eventId,
            onDismiss = { ZapController.dismiss() },
        )
    }
}

private val PRESET_SATS = listOf(21L, 100L, 500L, 1_000L, 5_000L, 21_000L)

@Composable
private fun ZapModal(
    recipientPubkey: String,
    eventId: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    val metadataMap by AppModule.nostrRepository.userMetadata.collectAsState()
    val metadata = metadataMap[recipientPubkey]
    val recipientName = metadata?.displayName ?: metadata?.name ?: shortNpub(recipientPubkey)

    LaunchedEffect(recipientPubkey) {
        if (metadataMap[recipientPubkey] == null) {
            AppModule.nostrRepository.requestUserMetadata(setOf(recipientPubkey))
        }
    }

    var amountText by remember { mutableStateOf("21") }
    var comment by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invoice by remember { mutableStateOf<ZapManager.ZapInvoice?>(null) }
    var paid by remember { mutableStateOf(false) }

    val amountSats = amountText.toLongOrNull() ?: 0L

    // Once an invoice exists, watch the relays for its zap receipt to confirm payment.
    LaunchedEffect(invoice?.bolt11) {
        val inv = invoice ?: return@LaunchedEffect
        if (AppModule.nostrRepository.watchZapPayment(inv.bolt11, recipientPubkey, eventId)) {
            paid = true
        }
    }
    // Auto-close shortly after a confirmed payment.
    LaunchedEffect(paid) {
        if (paid) {
            delay(2_000)
            onDismiss()
        }
    }

    fun send() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            when (val r = AppModule.nostrRepository.requestZapInvoice(recipientPubkey, amountSats, comment, eventId)) {
                is Result.Success -> invoice = r.data
                is Result.Error -> error = r.error.message
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(0.92f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xxl),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Bolt,
                                contentDescription = null,
                                tint = NostrordColors.Warning,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = "Zap $recipientName",
                                color = NostrordColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = NostrordColors.TextSecondary)
                        }
                    }

                    Spacer(Modifier.height(Spacing.lg))

                    val readyInvoice = invoice
                    when {
                        readyInvoice == null -> ZapAmountStep(
                            amountText = amountText,
                            onAmountChange = { amountText = it.filter { c -> c.isDigit() } },
                            comment = comment,
                            onCommentChange = { comment = it },
                            loading = loading,
                            error = error,
                            onSend = ::send,
                            sendEnabled = amountSats > 0L,
                        )

                        paid -> ZapSuccessStep(amountMsats = readyInvoice.amountMsats)

                        else -> ZapInvoiceStep(
                            invoice = readyInvoice,
                            onOpenWallet = {
                                runCatching { uriHandler.openUri("lightning:${readyInvoice.bolt11}") }
                                    .onFailure { error = "No Lightning wallet found. Scan the QR or copy the invoice." }
                            },
                            onCopy = { clipboard.setText(AnnotatedString(readyInvoice.bolt11)) },
                            onDone = onDismiss,
                            error = error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZapAmountStep(
    amountText: String,
    onAmountChange: (String) -> Unit,
    comment: String,
    onCommentChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    Text(
        text = "Amount (sats)",
        color = NostrordColors.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(Spacing.sm))

    // Preset chips, two rows of three.
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PRESET_SATS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                row.forEach { preset ->
                    val selected = amountText == preset.toString()
                    OutlinedButton(
                        onClick = { onAmountChange(preset.toString()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) NostrordColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
                            contentColor = NostrordColors.TextPrimary,
                        ),
                    ) {
                        Text("$preset", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.md))

    OutlinedTextField(
        value = amountText,
        onValueChange = onAmountChange,
        label = { Text("Custom amount") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(Spacing.md))

    OutlinedTextField(
        value = comment,
        onValueChange = onCommentChange,
        label = { Text("Comment (optional)") },
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )

    if (error != null) {
        Spacer(Modifier.height(Spacing.md))
        Text(text = error, color = NostrordColors.Error, fontSize = 13.sp)
    }

    Spacer(Modifier.height(Spacing.lg))

    Button(
        onClick = onSend,
        enabled = sendEnabled && !loading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Get invoice", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ZapInvoiceStep(
    invoice: ZapManager.ZapInvoice,
    onOpenWallet: () -> Unit,
    onCopy: () -> Unit,
    onDone: () -> Unit,
    error: String?,
) {
    Text(
        text = "${invoice.amountMsats / 1000} sats invoice ready",
        color = NostrordColors.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "Pay with your Lightning wallet to complete the zap.",
        color = NostrordColors.TextSecondary,
        fontSize = 13.sp,
    )

    Spacer(Modifier.height(Spacing.lg))

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(data = "lightning:${invoice.bolt11}", size = 220.dp)
    }

    if (error != null) {
        Spacer(Modifier.height(Spacing.md))
        Text(text = error, color = NostrordColors.Error, fontSize = 13.sp)
    }

    Spacer(Modifier.height(Spacing.lg))

    Button(
        onClick = onOpenWallet,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
    ) {
        Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text("Open in wallet", fontWeight = FontWeight.SemiBold)
    }

    Spacer(Modifier.height(Spacing.sm))

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NostrordColors.TextPrimary),
        ) {
            Text("Copy invoice", fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NostrordColors.TextPrimary),
        ) {
            Text("Done", fontSize = 13.sp)
        }
    }

    Spacer(Modifier.height(Spacing.md))

    // Live confirmation indicator — flips to ZapSuccessStep when the receipt arrives.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = NostrordColors.TextSecondary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text("Waiting for payment…", color = NostrordColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ZapSuccessStep(amountMsats: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Bolt,
            contentDescription = null,
            tint = NostrordColors.Warning,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "Zap received",
            color = NostrordColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "${amountMsats / 1000} sats paid ⚡",
            color = NostrordColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

/** Compact zap-total chip shown under a message, mirroring reaction badges. */
@Composable
fun ZapBadge(
    totalMsats: Long,
    count: Int,
    zappedByMe: Boolean,
    onClick: () -> Unit,
) {
    val sats = totalMsats / 1000
    val container = if (zappedByMe) NostrordColors.Warning.copy(alpha = 0.18f) else NostrordColors.InputBackground
    val tint = if (zappedByMe) NostrordColors.Warning else NostrordColors.TextSecondary
    Row(
        modifier = Modifier
            .padding(top = Spacing.xs)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Bolt,
            contentDescription = "Zaps",
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (count > 1) "${formatSatsShort(sats)} · $count" else formatSatsShort(sats),
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatSatsShort(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M"
    sats >= 10_000 -> "${sats / 1_000}k"
    else -> sats.toString()
}
