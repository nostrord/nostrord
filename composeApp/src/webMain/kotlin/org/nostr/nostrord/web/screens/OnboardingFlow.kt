package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.onboarding.onboardingFollowSuggestions
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.FollowAllButton
import org.nostr.nostrord.web.components.FollowSuggestionCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

private val STEPS = listOf("Welcome", "Who to follow", "Groups")

external interface OnboardingFlowProps : Props {
    /** "Skip for now" / "Done" without joining: session override, lands on Home. */
    var onSkip: () -> Unit

    /** Join by invite/naddr/address; success flips the kind:10009 gate to Home upstream. */
    var onJoin: (String, (Result<Unit>) -> Unit) -> Unit
}

/**
 * Full-page onboarding wizard (prototype Onboarding, without the follow-pack /
 * avatar collection steps): progress bars + step label, Welcome and Groups steps,
 * and the footer with Back / Skip and the primary action. Mirrors the Compose
 * OnboardingFlowScreen; gating lives in AppViewModel.needsOnboarding.
 */
val OnboardingFlow =
    FC<OnboardingFlowProps> { props ->
        val (step, setStep) = useState { 0 }
        val (joinInput, setJoinInput) = useState { "" }
        val (joining, setJoining) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun join() {
            if (joinInput.isBlank() || joining) return
            setJoining(true)
            setError(null)
            props.onJoin(joinInput.trim()) { result ->
                setJoining(false)
                // Success flips the onboarding gate upstream and lands on Home.
                setError(result.exceptionOrNull()?.message)
            }
        }

        div {
            className = ClassName("onb-page")
            div {
                className = ClassName("onb-steps")
                STEPS.forEachIndexed { index, _ ->
                    span {
                        key = index.toString()
                        className = ClassName(if (index <= step) "onb-step-bar active" else "onb-step-bar")
                    }
                }
            }
            div {
                className = ClassName("onb-step-label")
                +"Step ${step + 1} of ${STEPS.size} · ${STEPS[step]}"
            }

            div {
                className = ClassName("onb-body")
                when (step) {
                    0 -> welcomeStep()
                    1 -> WhoToFollowStep {}
                    else -> groupsStep(joinInput, joining, error, { setJoinInput(it) }, { setError(it) }, ::join)
                }
            }

            div {
                className = ClassName("onb-footer")
                div {
                    className = ClassName("onb-footer-inner")
                    if (step > 0) {
                        button {
                            className = ClassName("btn-text")
                            onClick = { setStep(step - 1) }
                            +"Back"
                        }
                    } else {
                        button {
                            className = ClassName("btn-text")
                            onClick = { props.onSkip() }
                            +"Skip for now"
                        }
                    }
                    button {
                        className = ClassName("btn-primary btn-lg")
                        onClick = { if (step < STEPS.size - 1) setStep(step + 1) else props.onSkip() }
                        +(if (step < STEPS.size - 1) "Continue" else "Done")
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.welcomeStep() {
    div {
        className = ClassName("onb-hero")
        img {
            className = ClassName("onb-hero-logo")
            src = "icon-192.png"
            alt = "Nostrord"
        }
        h1 {
            className = ClassName("onb-title")
            +"Welcome to Nostrord"
        }
        p {
            className = ClassName("onb-sub")
            +"Your account is ready and everything technical is handled in the background. We'll connect you to people, and they lead you to the right groups."
        }
        div {
            className = ClassName("onb-hints")
            hintRow(Ic.People, "Follow some people", "Ready-made packs to start you off with a network")
            hintRow(Ic.Forum, "See their groups", "We suggest the groups where the people you follow are")
            hintRow(Ic.Link, "Or join by invite", "Paste a group link to jump straight in")
        }
    }
}

private fun ChildrenBuilder.hintRow(
    ic: Ic,
    title: String,
    description: String,
) {
    div {
        className = ClassName("hint-row")
        icon(ic)
        div {
            div {
                className = ClassName("hint-row-title")
                +title
            }
            div {
                className = ClassName("hint-row-desc")
                +description
            }
        }
    }
}

private val WhoToFollowStep =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val following = useStateFlow(repo.following)
        val metadata = useStateFlow(repo.userMetadata)

        useEffect(Unit) {
            // Pull the existing kind:3 so already-followed people show as "Following", and so
            // the first follow tap skips the contact-list publish's initial fetch wait.
            launchApp { repo.requestContactList() }
            val pubkeys = onboardingFollowSuggestions.map { it.pubkey }.filter { it.isNotBlank() }.toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }

        h2 {
            className = ClassName("onb-section-title")
            +"Who to follow"
        }
        p {
            className = ClassName("onb-section-sub")
            +"Pick a few people to follow: that's what unlocks group discovery."
        }
        div {
            className = ClassName("follow-list-head")
            FollowAllButton {
                people = onboardingFollowSuggestions
                this.following = following
            }
        }
        div {
            className = ClassName("onb-pack-list")
            onboardingFollowSuggestions.forEach { person ->
                FollowSuggestionCard {
                    key = person.npub
                    this.person = person
                    pictureUrl = metadata[person.pubkey]?.picture
                    isFollowing = person.pubkey in following
                }
            }
        }
        p {
            className = ClassName("onb-skip-note")
            +"Follow at least one person to see group suggestions in the next step."
        }
    }

private fun ChildrenBuilder.groupsStep(
    joinInput: String,
    joining: Boolean,
    error: String?,
    setJoinInput: (String) -> Unit,
    setError: (String?) -> Unit,
    join: () -> Unit,
) {
    h2 {
        className = ClassName("onb-section-title")
        +"Find your group"
    }
    p {
        className = ClassName("onb-section-sub")
        +"Join with an invite link, a naddr address, or a group ID. You can leave at any time."
    }
    div {
        className = ClassName("join-card")
        div {
            className = ClassName("join-card-title")
            icon(Ic.Link)
            +"Join by link, naddr or group ID"
        }
        formError(error)
        div {
            className = ClassName("join-row")
            iconInput(
                ic = Ic.Link,
                type = InputType.text,
                placeholder = "naddr1... or wss://relay'groupId",
                value = joinInput,
                onChange = {
                    setJoinInput(it)
                    setError(null)
                },
                onEnter = { join() },
            )
            button {
                className = ClassName("btn-secondary btn-lg")
                disabled = joinInput.isBlank() || joining
                onClick = { join() }
                if (joining) {
                    span { className = ClassName("btn-spinner") }
                }
                +(if (joining) "Joining…" else "Join")
            }
        }
        formHint("Accepts an invite link, a NIP-19 naddr address, or relay'groupId.")
    }
    p {
        className = ClassName("onb-skip-note")
        +"No invite yet? Skip for now and create your own group from Home."
    }
}
