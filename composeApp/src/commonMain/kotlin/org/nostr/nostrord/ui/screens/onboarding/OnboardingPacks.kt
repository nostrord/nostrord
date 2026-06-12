package org.nostr.nostrord.ui.screens.onboarding

/**
 * Placeholder follow packs for the onboarding's "Who to follow" step. Layout-only
 * for now: the cards render this dummy data on both UIs, with no avatar fetching
 * or follow actions — the real NIP-51 follow-pack wiring replaces this list later.
 */
data class OnboardingPack(
    val emoji: String,
    val name: String,
    val description: String,
    val people: Int,
)

val onboardingFollowPacks =
    listOf(
        OnboardingPack("🟣", "Nostr OGs", "Protocol builders and long-time nostriches", 21),
        OnboardingPack("💻", "Developers", "People building clients, relays and tools", 18),
        OnboardingPack("🎨", "Artists & Creators", "Art, music and original content on nostr", 14),
        OnboardingPack("📰", "News & Curation", "Curators who surface what matters", 9),
    )
