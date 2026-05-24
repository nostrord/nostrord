package org.nostr.nostrord.web.components

import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * App-wide entry point for opening the zap modal from anywhere (message zap badge,
 * context menu, profile) without threading modal state through every screen. Mirrors the
 * Compose `ZapController` + `ZapModalHost`: a single [ZapModalHost] at the app root renders
 * whatever target is active.
 */
object WebZapController {
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

private val PRESET_SATS = listOf(21L, 100L, 500L, 1_000L, 5_000L, 21_000L)

/** Place once at the app root. Renders the zap modal whenever [WebZapController] has a target. */
val ZapModalHost =
    FC<Props> {
        val target = useStateFlow(WebZapController.active)
        if (target != null) {
            ZapModal {
                recipientPubkey = target.recipientPubkey
                eventId = target.eventId
                onDismiss = { WebZapController.dismiss() }
            }
        }
    }

private external interface ZapModalProps : Props {
    var recipientPubkey: String
    var eventId: String?
    var onDismiss: () -> Unit
}

private val ZapModal =
    FC<ZapModalProps> { props ->
        val repo = AppModule.nostrRepository
        val userMetadata = useStateFlow(repo.userMetadata)
        val metadata = userMetadata[props.recipientPubkey]
        val recipientName =
            metadata?.displayName?.ifBlank { null }
                ?: metadata?.name?.ifBlank { null }
                ?: (props.recipientPubkey.take(8) + "…")

        val (amountText, setAmountText) = useState { "21" }
        val (comment, setComment) = useState { "" }
        val (loading, setLoading) = useState { false }
        val (error, setError) = useState<String?> { null }
        val (invoice, setInvoice) = useState<ZapManager.ZapInvoice?> { null }
        val (paid, setPaid) = useState { false }

        val amountSats = amountText.toLongOrNull() ?: 0L

        // Fetch the recipient's metadata (for name + Lightning address) if we don't have it.
        useEffectOnce {
            if (userMetadata[props.recipientPubkey] == null) {
                launchApp { repo.requestUserMetadata(setOf(props.recipientPubkey)) }
            }
        }

        // Once an invoice exists, watch the relays for its zap receipt to confirm payment.
        useEffect(invoice?.bolt11) {
            val inv = invoice ?: return@useEffect
            launchApp {
                if (repo.watchZapPayment(inv.bolt11, props.recipientPubkey, props.eventId)) {
                    setPaid(true)
                }
            }
        }

        // Auto-close shortly after a confirmed payment.
        useEffect(paid) {
            if (paid) {
                launchApp {
                    delay(2_000)
                    props.onDismiss()
                }
            }
        }

        fun send() {
            if (loading) return
            setLoading(true)
            setError(null)
            launchApp {
                when (val r = repo.requestZapInvoice(props.recipientPubkey, amountSats, comment, props.eventId)) {
                    is Result.Success -> setInvoice(r.data)
                    is Result.Error -> setError(r.error.message)
                }
                setLoading(false)
            }
        }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onDismiss() }
            div {
                className = ClassName("modal-card zap-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-title")
                        icon(Ic.Bolt)
                        +"Zap $recipientName"
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onDismiss() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("zap-body")
                    val inv = invoice
                    when {
                        inv == null ->
                            amountStep(
                                amountText,
                                { setAmountText(it) },
                                comment,
                                { setComment(it) },
                                loading,
                                error,
                                amountSats > 0L,
                            ) { send() }

                        paid -> successStep(inv.amountMsats)
                        else -> invoiceStep(inv, error, props.onDismiss) { setError(it) }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.amountStep(
    amountText: String,
    setAmountText: (String) -> Unit,
    comment: String,
    setComment: (String) -> Unit,
    loading: Boolean,
    error: String?,
    sendEnabled: Boolean,
    onSend: () -> Unit,
) {
    p {
        className = ClassName("field-label")
        +"Amount (sats)"
    }
    div {
        className = ClassName("zap-presets")
        PRESET_SATS.forEach { preset ->
            val selected = amountText == preset.toString()
            button {
                className = ClassName(if (selected) "zap-preset selected" else "zap-preset")
                onClick = { setAmountText(preset.toString()) }
                +preset.toString()
            }
        }
    }
    input {
        className = ClassName("modal-input")
        placeholder = "Custom amount"
        value = amountText
        onChange = { event -> setAmountText(event.currentTarget.value.filter { it.isDigit() }) }
    }
    textarea {
        className = ClassName("modal-textarea")
        placeholder = "Comment (optional)"
        value = comment
        onChange = { event -> setComment(event.currentTarget.value) }
    }
    if (error != null) {
        p {
            className = ClassName("modal-error")
            +error
        }
    }
    button {
        className = ClassName("btn-primary block")
        disabled = !sendEnabled || loading
        onClick = { onSend() }
        if (loading) {
            +"Getting invoice…"
        } else {
            icon(Ic.Bolt)
            +"Get invoice"
        }
    }
}

private fun ChildrenBuilder.invoiceStep(
    invoice: ZapManager.ZapInvoice,
    error: String?,
    onDone: () -> Unit,
    setError: (String?) -> Unit,
) {
    p {
        className = ClassName("zap-invoice-title")
        +"${invoice.amountMsats / 1000} sats invoice ready"
    }
    p {
        className = ClassName("zap-invoice-sub")
        +"Pay with your Lightning wallet to complete the zap."
    }
    div {
        className = ClassName("zap-qr-wrap")
        img {
            className = ClassName("qr-img")
            src = qrDataUrl("lightning:${invoice.bolt11}")
            alt = "Lightning invoice QR code"
        }
    }
    if (error != null) {
        p {
            className = ClassName("modal-error")
            +error
        }
    }
    button {
        className = ClassName("btn-primary block")
        onClick = {
            runCatching { window.location.href = "lightning:${invoice.bolt11}" }
                .onFailure { setError("No Lightning wallet found. Scan the QR or copy the invoice.") }
        }
        icon(Ic.Bolt)
        +"Open in wallet"
    }
    div {
        className = ClassName("zap-invoice-actions")
        button {
            className = ClassName("btn-text")
            onClick = { copyText(invoice.bolt11) }
            +"Copy invoice"
        }
        button {
            className = ClassName("btn-text")
            onClick = { onDone() }
            +"Done"
        }
    }
    div {
        className = ClassName("zap-waiting")
        span { className = ClassName("zap-spinner") }
        span { +"Waiting for payment…" }
    }
}

private fun ChildrenBuilder.successStep(amountMsats: Long) {
    div {
        className = ClassName("zap-success")
        icon(Ic.Bolt, "ico zap-success-ico")
        div {
            className = ClassName("zap-success-title")
            +"Zap received"
        }
        div {
            className = ClassName("zap-success-sub")
            +"${amountMsats / 1000} sats paid "
            icon(Ic.Bolt)
        }
    }
}

/** Compact zap-total chip shown under a message, mirroring reaction badges. */
fun ChildrenBuilder.zapBadge(totalMsats: Long, count: Int, zappedByMe: Boolean, onSelect: () -> Unit) {
    val sats = totalMsats / 1000
    button {
        className = ClassName(if (zappedByMe) "zap-badge mine" else "zap-badge")
        onClick = { onSelect() }
        icon(Ic.Bolt, "ico zap-badge-bolt")
        +(if (count > 1) "${formatSatsShort(sats)} · $count" else formatSatsShort(sats))
    }
}

private fun copyText(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

private fun formatSatsShort(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M"
    sats >= 10_000 -> "${sats / 1_000}k"
    else -> sats.toString()
}
