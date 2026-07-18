package org.nostr.nostrord.auth.pomegranate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS auth WebView (WKWebView). A user script injected at document start (so it survives the
 * OAuth navigation) shims window.opener and routes postMessage through a script message
 * handler. UA is spoofed to a desktop browser. Written for the macOS build/validation session;
 * not compiled on Linux (see POMEGRANATE-NATIVE-TODO.md).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PomegranateAuthWebView(
    url: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    onResult: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    var delivered = false
    val handler =
        remember {
            object : NSObject(), WKScriptMessageHandlerProtocol {
                override fun userContentController(
                    userContentController: WKUserContentController,
                    didReceiveScriptMessage: WKScriptMessage,
                ) {
                    val value = didReceiveScriptMessage.body as? String ?: return
                    if (value.isNotEmpty() && !delivered) {
                        delivered = true
                        onResult(value)
                    }
                }
            }
        }

    UIKitView(
        modifier = pomegranateWebViewModifier,
        factory = {
            val controller = WKUserContentController()
            controller.addScriptMessageHandler(handler, name = "NostrordPomegranate")
            val script =
                "window.__nostrordReceive = function(v){ window.webkit.messageHandlers.NostrordPomegranate.postMessage(v); };\n" +
                    pomegranateOpenerInjection(mode, expectedOrigin)
            controller.addUserScript(
                WKUserScript(
                    source = script,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = false,
                ),
            )
            val config = WKWebViewConfiguration()
            config.userContentController = controller

            WKWebView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config).apply {
                customUserAgent = POMEGRANATE_WEBVIEW_USER_AGENT
                NSURL.URLWithString(url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
    )
}
