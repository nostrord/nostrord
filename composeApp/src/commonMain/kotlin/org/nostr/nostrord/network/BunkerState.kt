package org.nostr.nostrord.network

/** Why the app currently can't sign through the active NIP-46 bunker. */
enum class BunkerUnreachableReason {
    /** Couldn't open a WebSocket to any of the bunker's relays (network / VPN / relay down). */
    RelaysUnreachable,

    /** Relays connected but the signer never answered (offline or connection revoked). */
    SignerNotResponding,

    /** The signer answered but explicitly refused to sign (permission revoked in the signer app). */
    PermissionDenied,

    /** Cause couldn't be determined (e.g. corrupt saved credentials). */
    Unknown,
}

/**
 * Lifecycle of the active account's NIP-46 bunker connection. Single source of
 * truth in [AuthManager]; the legacy `isBunkerConnected` boolean is derived from
 * it. Drives [org.nostr.nostrord.ui.components.BunkerStatusBanner].
 */
sealed interface BunkerState {
    /** No bunker session (logged out, or a local / NIP-07 account is active). */
    data object Inactive : BunkerState

    /** Signer reachable and ready to sign. */
    data object Connected : BunkerState

    /** A reconnect attempt is in flight; the banner shows an inline spinner. */
    data object Reconnecting : BunkerState

    /** Signer unreachable; the banner explains [reason] and offers actions. */
    data class Unreachable(val reason: BunkerUnreachableReason) : BunkerState
}

/** What the bunker-unreachable banner says and which actions it offers. */
data class BunkerBannerCopy(
    val title: String,
    val body: String,
    /** False when reconnecting can never succeed (pomegranate signer deleted): only Log out is shown. */
    val canReconnect: Boolean,
)

/**
 * Banner copy shared by the native and web banners so the two never drift.
 * [pomegranateGone] is the deliberate-disconnect case: the account was deleted on
 * the pomegranate central server, so the signer is gone for good and the only way
 * forward is the exported nsec (or logging out).
 */
fun bunkerBannerCopy(
    reconnecting: Boolean,
    reason: BunkerUnreachableReason?,
    pomegranateGone: Boolean,
): BunkerBannerCopy = when {
    pomegranateGone ->
        BunkerBannerCopy(
            title = "This account's signer is gone",
            body =
            "This account was disconnected from its Google signer, so it can read but no longer sign. " +
                "Log out and sign in with the exported private key (nsec) to keep using it.",
            canReconnect = false,
        )

    reconnecting ->
        BunkerBannerCopy(
            title = "Reconnecting to your signer…",
            body = "Trying to restore the connection to your bunker…",
            canReconnect = true,
        )

    reason == BunkerUnreachableReason.RelaysUnreachable ->
        BunkerBannerCopy(
            title = "Can't reach the bunker relays",
            body = "Your bunker's relays didn't respond. Check your internet or VPN, then reconnect.",
            canReconnect = true,
        )

    reason == BunkerUnreachableReason.PermissionDenied ->
        BunkerBannerCopy(
            title = "Your signer refused to sign",
            body = "The signer rejected this app's request. Re-grant permissions in your signer app, then reconnect.",
            canReconnect = true,
        )

    else ->
        BunkerBannerCopy(
            title = "Can't reach your signer",
            body = "Your bunker didn't respond. It may be offline, or its connection was removed. Reconnect to try again.",
            canReconnect = true,
        )
}
