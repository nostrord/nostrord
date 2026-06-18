package org.nostr.nostrord

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.toHash
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.WebApp
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.runCacheStoreSelfTest
import org.nostr.nostrord.web.theme.applyDimenTokens
import org.nostr.nostrord.web.theme.applyTheme
import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

/**
 * Parse URL query parameters for deep linking.
 * Supports: /?relay=groups.hzrd149.com (opens the relay)
 *           /?view=notifications
 * Legacy group links (/?relay=X&group=Y[&code=Z]) redirect to the canonical
 * hash route #/g/X/Y[?invite=Z] before render.
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

    // Legacy ?relay=&group= group links redirect to the canonical #/g/<relay>/<id>
    // hash route (AppFrame owns it: relay switch, open, ?invite= auto-join). The
    // query is stripped so the URL shown/copied is the new form.
    if (groupId != null) {
        val route = GroupRoute(relayUrl = relayUrl, groupId = groupId, inviteCode = inviteCode)
        window.history.replaceState(null, "", window.location.pathname + route.toHash())
        return
    }

    val context =
        when {
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
    // Dev affordance: `?cachetest` runs the IndexedDB cache store self-test in this real
    // browser instead of mounting the app, and reports the result via window/title for a
    // headless driver to read. Inert in normal use.
    if (window.location.search.contains("cachetest")) {
        launchApp {
            val result = runCacheStoreSelfTest()
            window.asDynamic().__cacheTestResult = result
            document.title = "cachetest: $result"
        }
        return
    }

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
