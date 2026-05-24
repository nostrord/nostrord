package org.nostr.nostrord.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.qrDataUrl
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

external interface BunkerQrProps : Props {
    /** Called when the remote signer connects and login completes. */
    var onSuccess: () -> Unit
}

/**
 * NIP-46 nostrconnect (QR) login — creates a session, shows the `nostrconnect://` URI as a
 * QR code, and blocks on `completeNostrConnectLogin` until the signer connects. Disconnects
 * the client on unmount.
 */
val BunkerQr =
    FC<BunkerQrProps> { props ->
        val (uri, setUri) = useState<String?> { null }
        val (error, setError) = useState<String?> { null }

        useEffectOnce {
            launchApp {
                try {
                    val (connectUri, client) = AppModule.nostrRepository.createNostrConnectSession()
                    setUri(connectUri)
                    AppModule.nostrRepository.completeNostrConnectLogin(client)
                    props.onSuccess()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    setError("Connection failed: ${e.message}")
                }
            }
        }

        div {
            className = ClassName("bunker-qr")
            p {
                className = ClassName("bunker-scan")
                +"Scan with your signer app"
            }
            p {
                className = ClassName("bunker-scan-sub")
                +"(Amber, nsec.app, etc.)"
            }
            when {
                error != null ->
                    div {
                        className = ClassName("qr-placeholder")
                        icon(Ic.Warning)
                    }
                uri == null ->
                    div {
                        className = ClassName("qr-placeholder")
                        +"…"
                    }
                else ->
                    img {
                        className = ClassName("qr-img")
                        src = qrDataUrl(uri)
                        alt = "nostrconnect QR code"
                    }
            }
            if (error != null) {
                p {
                    className = ClassName("login-error")
                    +error
                }
            }
            uri?.let { connectUri ->
                button {
                    className = ClassName("login-outline-success")
                    onClick = {
                        val clip = window.navigator.asDynamic().clipboard
                        if (clip != null) clip.writeText(connectUri)
                    }
                    +"Copy nostrconnect URI"
                }
            } ?: run {
                if (error == null) {
                    span {
                        className = ClassName("bunker-scan-sub")
                        +"Generating link…"
                    }
                }
            }
        }
    }
