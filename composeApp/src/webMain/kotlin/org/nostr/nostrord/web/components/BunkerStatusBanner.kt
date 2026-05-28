package org.nostr.nostrord.web.components

import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.BunkerState
import org.nostr.nostrord.network.BunkerUnreachableReason
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Floating banner shown when the active account signs through a NIP-46 bunker the
 * client can't reach. Mirrors native `BunkerStatusBanner` 1:1 — reads the
 * AuthManager state machine via `repo.bunkerState`, renders title/body based on
 * the unreachable reason, and offers two actions:
 *
 *  - **Reconnect** — calls `repo.ensureBunkerConnected()`. The new bounded
 *    attempt (relays 8s + signer 12s, mutex-guarded) means the spinner can never
 *    outlive the reconnect window (issue #85).
 *  - **Log out** — removes the active account if registered, falling back to a
 *    plain logout — same logic the native banner uses.
 *
 * Suppressed during cold-boot bunker verification (a full-screen loader covers
 * the same window), and as soon as a non-bunker session becomes active.
 */
val BunkerStatusBanner =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val loggedIn = useStateFlow(repo.isLoggedIn)
        val state = useStateFlow(repo.bunkerState)
        val verifying = useStateFlow(repo.isBunkerVerifying)
        val session = useStateFlow(ActiveAccountManager.session)

        val activeIsBunker = session?.signer is NostrSigner.Bunker
        val reconnecting = state is BunkerState.Reconnecting
        val unreachable = state is BunkerState.Unreachable

        val show = loggedIn && activeIsBunker && !verifying && (unreachable || reconnecting)
        if (!show) return@FC

        val reason = (state as? BunkerState.Unreachable)?.reason
        val title = when {
            reconnecting -> "Reconnecting to your signer…"
            reason == BunkerUnreachableReason.RelaysUnreachable -> "Can't reach the bunker relays"
            else -> "Can't reach your signer"
        }
        val body = when {
            reconnecting -> "Trying to restore the connection to your bunker…"
            reason == BunkerUnreachableReason.RelaysUnreachable ->
                "Your bunker's relays didn't respond. Check your internet or VPN, then reconnect."
            else ->
                "Your bunker didn't respond. It may be offline, or its connection was removed. " +
                    "Reconnect to try again."
        }

        div {
            className = ClassName("bunker-banner")
            div {
                className = ClassName("bunker-banner-row")
                span {
                    className = ClassName("bunker-banner-icon")
                    icon(Ic.Warning)
                }
                div {
                    className = ClassName("bunker-banner-text")
                    div {
                        className = ClassName("bunker-banner-title")
                        +title
                    }
                    div {
                        className = ClassName("bunker-banner-body")
                        +body
                    }
                }
            }
            div {
                className = ClassName("bunker-banner-actions")
                button {
                    className = ClassName("bunker-banner-btn text")
                    onClick = {
                        // Same flow as the native banner's "Log out" — remove the
                        // active account if registered, fall back to a plain
                        // logout. Routes the UI back to login either way.
                        launchApp {
                            val activeId = AppModule.accountStore.activeId.value
                            if (activeId != null) {
                                AppModule.accountManager.removeAccount(activeId)
                            } else {
                                repo.logout()
                            }
                        }
                    }
                    +"Log out"
                }
                button {
                    className = ClassName(if (reconnecting) "bunker-banner-btn primary loading" else "bunker-banner-btn primary")
                    disabled = reconnecting
                    onClick = { launchApp { repo.ensureBunkerConnected() } }
                    if (reconnecting) {
                        span { className = ClassName("bunker-banner-spinner") }
                    } else {
                        span {
                            className = ClassName("bunker-banner-action-icon")
                            icon(Ic.Refresh)
                        }
                    }
                    +(if (reconnecting) "Reconnecting…" else "Reconnect")
                }
            }
        }
    }
