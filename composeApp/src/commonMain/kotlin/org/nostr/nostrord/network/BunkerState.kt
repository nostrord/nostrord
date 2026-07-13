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
