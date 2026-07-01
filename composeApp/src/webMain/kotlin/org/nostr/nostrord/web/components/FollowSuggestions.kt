package org.nostr.nostrord.web.components

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.onboarding.OnboardingFollowSuggestion
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

external interface FollowSuggestionCardProps : Props {
    var person: OnboardingFollowSuggestion

    /** Avatar URL from the user's kind:0 metadata; null shows the seeded gradient fallback. */
    var pictureUrl: String?
    var isFollowing: Boolean
}

/**
 * One "person to follow" row for the onboarding step and the Home "People" filter:
 * avatar, name, a short note, and a Follow / Following toggle. Wired to the real
 * NIP-02 follow actions; the seed list lives in [OnboardingFollowSuggestion].
 */
val FollowSuggestionCard =
    FC<FollowSuggestionCardProps> { props ->
        val person = props.person
        div {
            className = ClassName("follow-card")
            WebAvatar {
                url = props.pictureUrl
                seed = person.pubkey
                this.name = person.name
                cls = "follow-card-avatar"
            }
            div {
                className = ClassName("follow-card-body")
                div {
                    className = ClassName("follow-card-name")
                    +person.name
                }
                div {
                    className = ClassName("follow-card-note")
                    +person.note
                }
            }
            // Fire-and-forget on the app scope so the publish survives leaving the page;
            // the repository flips `following` optimistically, so the button updates at once.
            followToggleButton(props.isFollowing, isBusy = false) {
                if (person.pubkey.isBlank()) return@followToggleButton
                launchApp {
                    val repo = AppModule.nostrRepository
                    if (props.isFollowing) repo.unfollowUser(person.pubkey) else repo.followUser(person.pubkey)
                }
            }
        }
    }

external interface FollowAllButtonProps : Props {
    var people: List<OnboardingFollowSuggestion>
    var following: Set<String>
}

/**
 * "Follow all" action above a [FollowSuggestionCard] list. Publishes a single kind:3
 * with every not-yet-followed pubkey; reads as "Following all" once they all are.
 */
val FollowAllButton =
    FC<FollowAllButtonProps> { props ->
        val pending = props.people.map { it.pubkey }.filter { it.isNotBlank() && it !in props.following }
        button {
            className = ClassName("btn-secondary follow-all-btn")
            disabled = pending.isEmpty()
            onClick = {
                val batch = pending.toSet()
                // App scope + optimistic flip: reads "Following all" instantly and the single
                // kind:3 publishes in the background, surviving a quick navigation away.
                launchApp { AppModule.nostrRepository.followUsers(batch) }
            }
            if (pending.isEmpty()) +"Following all" else +"Follow all"
        }
    }
