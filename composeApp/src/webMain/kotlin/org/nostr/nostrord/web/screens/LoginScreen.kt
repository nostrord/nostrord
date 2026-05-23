package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.password
import kotlin.random.Random

private enum class LoginTab { Key, Extension, Bunker }

/**
 * Web login with the three methods the Compose app supports: private key (nsec/hex or a
 * freshly generated identity), NIP-07 browser extension, and NIP-46 bunker. All drive
 * the shared Kotlin auth logic via the bridge; on success `AuthManager.isLoggedIn` flips
 * and [WebApp] swaps to the app. NIP-46 QR/nostrconnect comes later — this is bunker://.
 */
val LoginScreen =
    FC<Props> {
        val (tab, setTab) = useState { LoginTab.Key }
        val (error, setError) = useState<String?> { null }
        val (busy, setBusy) = useState { false }
        val (secret, setSecret) = useState { "" }
        val (bunkerUrl, setBunkerUrl) = useState { "" }

        val extensionAvailable = Nip07.isAvailable()

        fun loginWithKey(privHex: String, isNewIdentity: Boolean) {
            setBusy(true)
            setError(null)
            val keyPair = KeyPair.fromPrivateKeyHex(privHex)
            launchApp {
                val result = AppModule.nostrRepository.loginSuspend(privHex, keyPair.publicKeyHex, isNewIdentity)
                setBusy(false)
                if (result is Result.Error) setError("Login failed. Check your key and try again.")
            }
        }

        fun loginWithExtension() {
            setBusy(true)
            setError(null)
            launchApp {
                try {
                    val pubkey = Nip07.getPublicKey()
                    val result = AppModule.nostrRepository.loginWithNip07(pubkey)
                    setBusy(false)
                    if (result is Result.Error) setError("Extension login failed.")
                } catch (e: Throwable) {
                    setBusy(false)
                    setError("Extension login was cancelled or unavailable.")
                }
            }
        }

        fun loginWithBunker(url: String) {
            setBusy(true)
            setError(null)
            launchApp {
                val result = AppModule.nostrRepository.loginWithBunker(url.trim())
                setBusy(false)
                if (result is Result.Error) setError("Bunker login failed. Check the connection string.")
            }
        }

        div {
            className = ClassName("app-shell")
            h1 { +"Sign in to Nostrord" }

            div {
                className = ClassName("row-actions")
                button {
                    className = ClassName(if (tab == LoginTab.Key) "tab tab-active" else "tab")
                    onClick = { setTab(LoginTab.Key) }
                    +"Private key"
                }
                if (extensionAvailable) {
                    button {
                        className = ClassName(if (tab == LoginTab.Extension) "tab tab-active" else "tab")
                        onClick = { setTab(LoginTab.Extension) }
                        +"Extension"
                    }
                }
                button {
                    className = ClassName(if (tab == LoginTab.Bunker) "tab tab-active" else "tab")
                    onClick = { setTab(LoginTab.Bunker) }
                    +"Bunker"
                }
            }

            when (tab) {
                LoginTab.Key -> {
                    p { +"Paste your nsec / private key, or create a new identity." }
                    input {
                        type = InputType.password
                        placeholder = "nsec1… or 64-char hex"
                        value = secret
                        disabled = busy
                        onChange = { event -> setSecret(event.currentTarget.value) }
                    }
                    div {
                        className = ClassName("row-actions")
                        button {
                            disabled = busy || secret.isBlank()
                            onClick = {
                                val entered = secret.trim()
                                val hex =
                                    if (entered.startsWith("nsec1")) {
                                        (Nip19.decode(entered) as? Nip19.Entity.Nsec)?.privkey
                                    } else {
                                        entered
                                    }
                                if (hex == null || hex.length != 64) {
                                    setError("Invalid nsec / private key.")
                                } else {
                                    loginWithKey(hex, isNewIdentity = false)
                                }
                            }
                            +(if (busy) "Signing in…" else "Sign in")
                        }
                        button {
                            className = ClassName("secondary")
                            disabled = busy
                            onClick = {
                                val newHex =
                                    Random.Default.nextBytes(32).joinToString("") { byte ->
                                        byte.toUByte().toString(16).padStart(2, '0')
                                    }
                                loginWithKey(newHex, isNewIdentity = true)
                            }
                            +"Create new identity"
                        }
                    }
                }

                LoginTab.Extension -> {
                    p { +"Sign in with your NIP-07 browser extension (Alby, nos2x, …)." }
                    button {
                        disabled = busy
                        onClick = { loginWithExtension() }
                        +(if (busy) "Waiting for extension…" else "Connect extension")
                    }
                }

                LoginTab.Bunker -> {
                    p { +"Paste a NIP-46 bunker:// connection string." }
                    input {
                        placeholder = "bunker://…"
                        value = bunkerUrl
                        disabled = busy
                        onChange = { event -> setBunkerUrl(event.currentTarget.value) }
                    }
                    button {
                        disabled = busy || bunkerUrl.isBlank()
                        onClick = { loginWithBunker(bunkerUrl) }
                        +(if (busy) "Connecting…" else "Connect bunker")
                    }
                }
            }

            error?.let { message ->
                p {
                    className = ClassName("error")
                    +message
                }
            }
        }
    }
