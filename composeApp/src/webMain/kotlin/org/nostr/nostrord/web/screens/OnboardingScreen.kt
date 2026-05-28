package org.nostr.nostrord.web.screens

import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

external interface OnboardingScreenProps : Props {
    var onAddRelay: () -> Unit
    var onAddRelayCustomUrl: () -> Unit
}

private fun ChildrenBuilder.onboardingStep(number: String, title: String, description: String) {
    div {
        className = ClassName("onboarding-step")
        div {
            className = ClassName("onboarding-step-badge")
            +number
        }
        div {
            className = ClassName("onboarding-step-text")
            div {
                className = ClassName("onboarding-step-title")
                +title
            }
            div {
                className = ClassName("onboarding-step-desc")
                +description
            }
        }
    }
}

/**
 * Onboarding content — layout-first React port of the Compose [OnboardingScreen]. Rendered
 * inside the shell's content column while the account has no relay yet (the rail + groups
 * sidebar stay visible around it). Logo + welcome + the three steps + the two add-relay
 * buttons; the buttons delegate to the shell ([onAddRelay] → Suggested tab,
 * [onAddRelayCustomUrl] → Custom URL tab), matching the Compose signature.
 */
val OnboardingScreen =
    FC<OnboardingScreenProps> { props ->
        div {
            className = ClassName("onboarding-page")
            div {
                className = ClassName("onboarding-inner")

                img {
                    className = ClassName("onboarding-logo")
                    src = "icon-192.png"
                    alt = "Nostrord"
                }
                h1 {
                    className = ClassName("onboarding-title")
                    +"Welcome to Nostrord"
                }
                p {
                    className = ClassName("onboarding-desc")
                    +(
                        "Group messaging on Nostr. Connect to relays, join communities, and chat. " +
                            "Open, decentralized, and without any central server."
                        )
                }

                div {
                    className = ClassName("onboarding-steps")
                    onboardingStep("1", "Add a Relay", "Connect to a Nostr relay that hosts groups")
                    onboardingStep("2", "Browse Groups", "Explore available groups on the relay")
                    onboardingStep("3", "Start Chatting", "Join groups and chat with the community")
                }

                button {
                    className = ClassName("onboarding-btn primary")
                    onClick = { props.onAddRelay() }
                    +"Add Your First Relay"
                }
                button {
                    className = ClassName("onboarding-btn secondary")
                    onClick = { props.onAddRelayCustomUrl() }
                    +"I already have a relay URL"
                }
            }
        }
    }
