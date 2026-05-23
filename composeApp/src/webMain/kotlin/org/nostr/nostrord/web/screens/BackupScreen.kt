package org.nostr.nostrord.web.screens

import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useState
import web.cssom.ClassName

/**
 * Backup keys — shows the npub (safe to share) and, for LOCAL accounts only, the nsec
 * behind a reveal. Remote-signer accounts (bunker/NIP-07) have no local private key.
 */
val BackupScreen =
    FC<Props> {
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val (revealed, setRevealed) = useState { false }

        val active = accounts.find { it.id == activeId }
        val isLocal = active?.authMethod == AuthMethod.LOCAL
        val npub = activeId?.let { Nip19.encodeNpub(it) }

        div {
            className = ClassName("app-shell")
            h1 { +"Backup keys" }

            p {
                className = ClassName("muted")
                +"Your public key (npub) is safe to share."
            }
            npub?.let {
                code {
                    className = ClassName("key-box")
                    +it
                }
            }

            if (!isLocal) {
                p {
                    className = ClassName("muted")
                    +"This account uses a remote signer (bunker / extension), so there is no local private key to back up."
                }
            } else {
                p {
                    className = ClassName("error")
                    +"Your private key (nsec) is your password — never share it. Anyone with it controls your account."
                }
                if (!revealed) {
                    button {
                        onClick = { setRevealed(true) }
                        +"Reveal private key"
                    }
                } else {
                    val raw = AppModule.nostrRepository.getPrivateKey()
                    val nsec = raw?.let { if (it.startsWith("nsec1")) it else Nip19.encodeNsec(it) }
                    code {
                        className = ClassName("key-box key-secret")
                        +(nsec ?: "unavailable")
                    }
                }
            }
        }
    }
