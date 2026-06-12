package org.nostr.nostrord

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.WebApp
import org.nostr.nostrord.web.theme.applyDimenTokens
import org.nostr.nostrord.web.theme.applyTheme
import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

/**
 * Parse URL query parameters for deep linking.
 * Supports: /?relay=groups.hzrd149.com&group=a45b2f
 *           /?relay=groups.hzrd149.com
 */
private fun parseDeepLinkFromUrl() {
    val search = window.location.search
    if (search.isBlank()) return

    val params =
        search.removePrefix("?").split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) {
                param.substring(0, idx) to param.substring(idx + 1)
            } else {
                param to ""
            }
        }

    val viewNotifications = params["view"] == "notifications"
    val relay = params["relay"]?.takeIf { it.isNotBlank() }
    val relayUrl = relay?.toRelayUrl()?.takeIf { it.isNotBlank() }

    // `?view=notifications` is cross-relay; the screen can boot without any
    // relay query param and the app falls back to persisted state.
    if (relayUrl == null) {
        if (viewNotifications) {
            StartupResolver.setExternalLaunchContext(ExternalLaunchContext.OpenNotifications())
        }
        return
    }

    val groupId = params["group"]?.takeIf { it.isNotBlank() }
    val inviteCode = params["code"]?.takeIf { it.isNotBlank() }
    val messageId = params["e"]?.takeIf { it.isNotBlank() }

    val context =
        when {
            groupId != null ->
                ExternalLaunchContext.OpenGroup(
                    groupId = groupId,
                    groupName = null,
                    relayUrl = relayUrl,
                    inviteCode = inviteCode,
                    messageId = messageId,
                )
            viewNotifications -> ExternalLaunchContext.OpenNotifications(relayUrl)
            else -> ExternalLaunchContext.OpenRelay(relayUrl)
        }
    StartupResolver.setExternalLaunchContext(context)
}

/**
 * Web entry point.
 *
 * The web UI was migrated off Compose Canvas (Skia/WASM) to real DOM via React
 * (kotlin-wrappers). Business logic is unchanged and consumed from commonMain
 * (AppModule); only the rendering layer is React. Fonts are now loaded by the
 * browser via CSS, so the old tiered Compose font-preloading is gone.
 */
fun main() {
    // Reconcile the web palette with the shared tokens (commonMain) before render,
    // overriding the cold-start fallback values in styles.css :root. Uses the persisted
    // theme preference so a light-theme user doesn't get a dark first paint.
    applyTheme(AppModule.appearanceSettings.theme.value)
    applyDimenTokens()
    parseDeepLinkFromUrl()
    val container =
        document.getElementById(ElementId("composeApplication"))
            ?: error("Root element #composeApplication not found")
    createRoot(container).render(WebApp.create())
}
