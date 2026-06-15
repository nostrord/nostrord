package org.nostr.nostrord.web.navigation

import kotlinx.browser.window
import org.nostr.nostrord.notifications.setDocumentTitle
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.navigation.parseHashRoute
import org.nostr.nostrord.ui.navigation.toHash

/**
 * Hash-based routes, GitHub Pages friendly: the fragment never reaches the server,
 * so deep links and refreshes on any route still load index.html with no rewrite
 * tricks. Home is the root (no hash); query-param deep links (?relay=…&group=…)
 * are untouched and compose with the hash.
 *
 * The hash MIRRORS the app's auth/navigation state rather than driving it: WebApp
 * and AppShell call [applyWebRoute] when the visible page changes, and entering the
 * app on any hash renders whatever the auth state allows, then normalizes the hash
 * (e.g. opening #/onboarding while logged out shows the login page as #/login).
 */
enum class WebRoute(
    val hash: String,
    val title: String,
) {
    Home("", "Nostrord"),
    Login("#/login", "Login - Nostrord"),
    Onboarding("#/onboarding", "Onboarding - Nostrord"),
}

fun applyWebRoute(route: WebRoute) {
    setDocumentTitle(route.title)
    if (window.location.hash != route.hash) {
        // replaceState keeps the mirror out of the back/forward stack (and avoids
        // the trailing '#' that assigning an empty location.hash leaves behind).
        window.history.replaceState(
            null,
            "",
            window.location.pathname + window.location.search + route.hash,
        )
    }
}

/** The page route in the current hash (#/g/…, #/u/…), or null on home/static routes. */
fun currentHashRoute(): HashRoute? = parseHashRoute(window.location.hash)

/**
 * USER navigation into a page (group, profile): assigns location.hash, which pushes
 * a history entry (back returns to the previous page) and fires hashchange so
 * AppFrame re-renders. Unlike [applyWebRoute]'s replaceState mirror, page navigation
 * is history the user expects to traverse.
 */
fun pushRoute(route: HashRoute) {
    window.location.hash = route.toHash()
}

/** USER navigation back to Home: pushes "#/" (parsed as no group). */
fun pushHome() {
    window.location.hash = "/"
}

/**
 * Mirrors a route into the hash WITHOUT a history entry or a hashchange event
 * (replaceState). Used for in-place state that should survive a refresh and be
 * shareable but not pollute the back stack, e.g. the Home discovery tab. A null
 * route (default Home) clears the fragment entirely.
 */
fun replaceHashRoute(route: HashRoute?) {
    val hash = route?.toHash().orEmpty()
    window.history.replaceState(
        null,
        "",
        window.location.pathname + window.location.search + hash,
    )
}

/**
 * Like [replaceHashRoute] but PUSHES a history entry, so back/forward traverses the
 * change. Used by the Home discovery tabs (My groups / From friends / Recommended /
 * People), which the user expects to navigate back through. A null route (Groups)
 * pushes the bare root. pushState fires no hashchange, so the caller updates its own
 * route state; the back/forward replay then fires hashchange and re-syncs.
 */
fun pushHashRoute(route: HashRoute?) {
    window.history.pushState(
        null,
        "",
        window.location.pathname + window.location.search + route?.toHash().orEmpty(),
    )
}

/**
 * Strips a consumed ?invite= from the hash without adding a history entry, so a
 * back/forward replay or a copied URL doesn't re-join.
 */
fun consumeInviteInHash(route: GroupRoute) {
    window.history.replaceState(
        null,
        "",
        window.location.pathname + window.location.search + route.copy(inviteCode = null).toHash(),
    )
}
