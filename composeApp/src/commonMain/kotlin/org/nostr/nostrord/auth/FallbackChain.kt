package org.nostr.nostrord.auth

/**
 * Walks [candidates] in order, calling [tryActivate] on each, and returns the
 * first one for which [tryActivate] returns true. Returns null if none accept.
 *
 * Used by the involuntary-deauth path (handleSessionInvalidated) and the
 * remove-active-account path to pick a fallback identity when the current one
 * is gone. Extracting the loop here makes the "rejects N-1, accepts Nth"
 * behavior independently testable without standing up a real AuthManager.
 *
 * [tryActivate] should perform the full activation attempt (load credentials,
 * open sockets if needed) and return true only when the candidate is fully
 * usable. It is invoked at most once per candidate, in list order, and
 * iteration stops as soon as one returns true.
 */
internal suspend fun <T> pickFirstSuccess(
    candidates: List<T>,
    tryActivate: suspend (T) -> Boolean,
): T? {
    for (candidate in candidates) {
        if (tryActivate(candidate)) return candidate
    }
    return null
}
