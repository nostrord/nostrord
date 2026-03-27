package org.nostr.nostrord.ui.util

import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.nostr.nostrord.utils.getImageUrl

/**
 * Canonical ImageRequest for relay icons.
 *
 * All relay icon composables (ServerRail, HomeScreenDesktop, AddRelayModal) MUST use this
 * function and nothing else. Identical parameters across all call sites guarantee a single
 * Coil memory-cache entry per icon URL — the first composable to load pays the network cost;
 * every subsequent composable on any screen gets an instant memory-cache hit.
 *
 * Invariants — never change these without updating every call site:
 *   - data          : getImageUrl(iconUrl) — deterministic, same string everywhere
 *   - memoryCachePolicy ENABLED — in-process reuse across screens
 *   - diskCachePolicy  ENABLED — reuse across app restarts
 *   - no .size()       — adding a size override changes the cache key and breaks sharing
 *   - no crossfade     — memory-cache hits must render in the same frame, not after a fade
 *   - no transformations, headers, or precision overrides
 */
fun buildRelayIconRequest(iconUrl: String, context: PlatformContext): ImageRequest =
    ImageRequest.Builder(context)
        .data(getImageUrl(iconUrl))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()
