package org.nostr.nostrord.ui.screens.onboarding

import org.nostr.nostrord.nostr.Nip19

/**
 * Curated "people to follow" seeds for the onboarding's "Who to follow" step. These are
 * well-known Nostr figures whose kind:10009 ("Simple Groups") lists point at legitimate,
 * recognizable NIP-29 groups, so following a few immediately surfaces real groups through
 * the social graph. Layout-only for now: the cards render this list on both UIs, with no
 * avatar fetching or follow actions yet (the real NIP-51 wiring replaces that later).
 */
data class OnboardingFollowSuggestion(
    val name: String,
    val npub: String,
    val note: String,
) {
    /** Hex pubkey decoded from [npub], for avatars and follow actions. */
    val pubkey: String = (Nip19.decode(npub) as? Nip19.Entity.Npub)?.pubkey.orEmpty()
}

val onboardingFollowSuggestions =
    listOf(
        OnboardingFollowSuggestion("verbiricha", "npub107jk7htfv243u0x5ynn43scq9wrxtaasmrwwa8lfu2ydwag6cx2quqncxg", "chachi dev: nostrord, nip-29, blossom, noStrudel"),
        OnboardingFollowSuggestion("Anjhc", "npub1f27g79lrpey73wtqa2pprn7vv3yveyytws08lxqe7pn0yuj8ppyqyk9swu", "app creator: nostrord, chachi, ecash, rootstock"),
        OnboardingFollowSuggestion("fiatjaf", "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", "Nostr creator: nip-29, chachi, ecash, jumble"),
        OnboardingFollowSuggestion("PABLOF7z", "npub1l2vyh47mk2p0qlsku7hg0vn29faehy9hy34ygaclpn66ukqp3afqutajft", "NDK: chachi, nip-29, highlighter"),
        OnboardingFollowSuggestion("hzrd149", "npub1ye5ptcxfyyxl5vjvdjar2ua3f0hynkjzpx552mu5snj3qmx5pzjscpknpr", "blossom and noStrudel: nostrord, chachi"),
        OnboardingFollowSuggestion("Sebastix", "npub1qe3e5wrvnsgpggtkytxteaqfprz0rgxr8c3l34kk3a9t7e2l3acslezefe", "active dev: inner.sebastix, nostrord, chachi"),
        OnboardingFollowSuggestion("hodlbod", "npub1jlrs53pkdfjnts29kveljul2sm0actt6n8dxrrzqcersttvcuv3qdjynqn", "Coracle: coracle spaces, bitcoinwalk"),
        OnboardingFollowSuggestion("Niel Liesmons", "npub149p5act9a5qm9p47elp8w8h3wpwn2d7s2xecw2ygnrxqp4wgsklq9g722q", "Nostr designer: grimoire, chachi, nostrord, Wisp"),
        OnboardingFollowSuggestion("dluvian", "npub1useke4f9maul5nf67dj0m9sq6jcsmnjzzk4ycvldwl4qss35fvgqjdk5ks", "Voyage: chachi, pyramid runners, ecash"),
        OnboardingFollowSuggestion("idsera", "npub1vxd0dfst8ljvwva2egrpc53ve8ru78v8aaxfpravchkexmfmmu3sqnrs50", "chachi, nostrord, ecash, rootstock, jumble"),
        OnboardingFollowSuggestion("utxo the webmaster", "npub1utx00neqgqln72j22kej3ux7803c2k986henvvha4thuwfkper4s7r50e8", "Self Hosting, Vibe Coding, Economy and Macro, grimoire"),
        OnboardingFollowSuggestion("Vitor Pamplona", "npub1gcxzte5zlkncx26j68ez60fzkvtkm9e0vrwdcvsjakxf9mu9qewqlfnj5z", "Amethyst: nosfabrica, 0xchat"),
        OnboardingFollowSuggestion("jb55", "npub1xtscya34g58tk0z605fvr788k263gsu6cy9x0mhnm87echrgufzsevkk5s", "Damus: chachi"),
    )
