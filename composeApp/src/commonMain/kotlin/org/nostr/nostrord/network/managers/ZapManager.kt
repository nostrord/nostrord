package org.nostr.nostrord.network.managers

import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis

/**
 * NIP-57 Lightning Zaps orchestration.
 *
 * Sending side: resolves the recipient's LNURL-pay endpoint, builds and signs a zap
 * request (kind 9734), and fetches a bolt11 invoice from the LNURL callback. Paying the
 * invoice is delegated to an external wallet — this manager never moves funds.
 *
 * Receiving side: aggregates incoming zap receipts (kind 9735, published by the
 * recipient's LNURL server) into [zaps], keyed by the zapped event id, so the UI can show
 * per-message zap totals.
 */
class ZapManager(
    private val metadataManager: MetadataManager,
    private val connectionManager: ConnectionManager,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val http = createHttpClient()

    companion object {
        private const val HTTP_TIMEOUT_MS = 15_000L
        private val DEFAULT_RELAYS =
            listOf("wss://relay.damus.io", "wss://nos.lol")
    }

    /** Aggregated zaps for a target event id: total amount + distinct zappers. */
    data class ZapInfo(
        val totalMsats: Long,
        val zappers: Set<String>,
        val count: Int,
    )

    /** A ready-to-pay invoice produced by [requestInvoice]. */
    data class ZapInvoice(
        val bolt11: String,
        val amountMsats: Long,
        val recipientPubkey: String,
        val eventId: String?,
        val comment: String,
    )

    // eventId -> aggregated zaps. Deduped by receipt id so relay echoes don't double count.
    private val _zaps = MutableStateFlow<Map<String, ZapInfo>>(emptyMap())
    val zaps: StateFlow<Map<String, ZapInfo>> = _zaps.asStateFlow()

    // Emits the bolt11 of every settled zap as its receipt arrives, so an open zap modal
    // can confirm payment. Buffered so emissions aren't dropped between poll cycles.
    private val _paidInvoices = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val paidInvoices: SharedFlow<String> = _paidInvoices.asSharedFlow()

    private val seenReceiptIds = LruCache<String, Boolean>(4000)

    /**
     * Resolve the recipient's LNURL-pay endpoint, build + sign a zap request (kind 9734),
     * and fetch a bolt11 invoice from the callback. The returned invoice must be paid by
     * an external wallet — this method does not pay it.
     *
     * @param amountSats whole-satoshi amount to zap.
     * @param eventId    the chat message being zapped, or null for a profile zap.
     */
    suspend fun requestInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
        senderPubkey: String,
        signEvent: suspend (Event) -> Event,
    ): Result<ZapInvoice> {
        if (amountSats <= 0L) {
            return Result.Error(AppError.Unknown("Enter an amount greater than zero"))
        }

        val metadata = metadataManager.getMetadata(recipientPubkey)
        val endpoint = Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06)
            ?: return Result.Error(AppError.Unknown("This user has no Lightning address set"))
        val (lnurlUrl, lnurlBech32) = endpoint
        val amountMsats = amountSats * 1_000L

        return try {
            withTimeout(HTTP_TIMEOUT_MS) {
                // 1. Fetch the LNURL-pay service description.
                val params = Nip57.parseLnurlPayParams(http.get(lnurlUrl).bodyAsText())
                    ?: return@withTimeout Result.Error(AppError.Unknown("Invalid Lightning address"))
                if (!params.allowsNostr || params.nostrPubkey.isNullOrBlank()) {
                    return@withTimeout Result.Error(
                        AppError.Unknown("This user's wallet does not support zaps"),
                    )
                }
                if (amountMsats < params.minSendableMsats) {
                    return@withTimeout Result.Error(
                        AppError.Unknown("Minimum is ${params.minSendableMsats / 1000} sats"),
                    )
                }
                if (amountMsats > params.maxSendableMsats) {
                    return@withTimeout Result.Error(
                        AppError.Unknown("Maximum is ${params.maxSendableMsats / 1000} sats"),
                    )
                }

                // 2. Build + sign the zap request (kind 9734). Not published to a relay.
                val cappedComment = if (params.commentAllowed > 0) comment.take(params.commentAllowed) else ""
                val request = Nip57.buildZapRequest(
                    senderPubkey = senderPubkey,
                    recipientPubkey = recipientPubkey,
                    amountMsats = amountMsats,
                    relays = receiptRelays(),
                    lnurlBech32 = lnurlBech32,
                    comment = cappedComment,
                    eventId = eventId,
                    createdAt = epochMillis() / 1000,
                )
                val signed = signEvent(request)

                // 3. Hit the callback with amount + the signed request to get the invoice.
                val callbackBody = http.get(params.callback) {
                    url {
                        parameters.append("amount", amountMsats.toString())
                        parameters.append("nostr", signed.toJsonString())
                        parameters.append("lnurl", lnurlBech32)
                    }
                }.bodyAsText()

                Nip57.parseCallbackError(callbackBody)?.let {
                    return@withTimeout Result.Error(AppError.Unknown(it))
                }
                val bolt11 = Nip57.parseInvoiceFromCallback(callbackBody)
                    ?: return@withTimeout Result.Error(AppError.Unknown("Wallet did not return an invoice"))

                Result.Success(
                    ZapInvoice(
                        bolt11 = bolt11,
                        amountMsats = amountMsats,
                        recipientPubkey = recipientPubkey,
                        eventId = eventId,
                        comment = cappedComment,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(AppError.Unknown("Could not reach the Lightning service: ${e.message}", e))
        }
    }

    /** Aggregate an incoming zap receipt (kind 9735) into [zaps], keyed by zapped event id. */
    fun handleZapReceipt(event: JsonObject) {
        val receiptId = event["id"]?.jsonPrimitive?.contentOrNull ?: return
        if (seenReceiptIds.get(receiptId) != null) return
        val parsed = Nip57.parseZapReceipt(event) ?: return
        seenReceiptIds.put(receiptId, true)

        // Confirm payment to any open zap modal (covers profile zaps too, which have no
        // zapped event id and aren't aggregated below).
        parsed.bolt11?.let { _paidInvoices.tryEmit(it) }

        // Aggregate per zapped event for the badge totals (needs an event id + amount).
        val target = parsed.zappedEventId ?: return
        val amount = parsed.amountMsats ?: return
        _zaps.update { current ->
            val existing = current[target]
            val zappers = parsed.zapperPubkey
                ?.let { (existing?.zappers ?: emptySet()) + it }
                ?: (existing?.zappers ?: emptySet())
            current + (
                target to ZapInfo(
                    totalMsats = (existing?.totalMsats ?: 0L) + amount,
                    zappers = zappers,
                    count = (existing?.count ?: 0) + 1,
                )
                )
        }
    }

    fun clear() {
        _zaps.value = emptyMap()
        seenReceiptIds.clear()
    }

    /**
     * Relays to request the zap receipt be published to (the 9734 `relays` tag), and the same
     * set we read the receipt back from: the relay of the group where the zapped message lives,
     * plus a few well-known general relays. A zap targets one group on one relay, so the single
     * group relay is what is relevant, not every group relay the account belongs to. The general
     * relays guarantee the receipt (kind 9735, no `h` tag) lands somewhere readable even if the
     * NIP-29 group relay rejects events without an `h` tag.
     */
    fun receiptRelays(): List<String> {
        val relays = LinkedHashSet<String>()
        connectionManager.currentRelayUrl.value.takeIf { it.isNotBlank() }?.let { relays.add(it) }
        relays.addAll(DEFAULT_RELAYS)
        return relays.toList()
    }
}
