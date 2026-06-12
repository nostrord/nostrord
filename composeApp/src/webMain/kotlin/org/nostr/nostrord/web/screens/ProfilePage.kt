package org.nostr.nostrord.web.screens

import js.objects.unsafeJso
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.IdentifierField
import org.nostr.nostrord.web.components.WebAvatar
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.Background
import web.cssom.ClassName

external interface ProfilePageProps : Props {
    var pubkey: String
    var onOpenGroup: (GroupRoute) -> Unit
    var onEditProfile: () -> Unit
}

/**
 * New-design user profile page (prototype Profile, /u/:pubkey): header, identity
 * card (gradient banner matching the user's avatar identity, avatar, name + ADMIN
 * badge, NIP-05, about, npub with copy) and the groups this user is in. Follow /
 * DM actions arrive with the follow and DM features. Mirrors the Compose
 * ui/screens/profile/ProfilePageScreen.
 */
val ProfilePage =
    FC<ProfilePageProps> { props ->
        val vm = useViewModel("profile-${props.pubkey}") { ProfilePageViewModel(AppModule.nostrRepository, props.pubkey) }
        val metadata = useStateFlow(vm.metadata)
        val groups = useStateFlow(vm.groupsWithUser)
        val isAdminSomewhere = useStateFlow(vm.isAdminSomewhere)

        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        div {
            className = ClassName("profile-page")
            div {
                className = ClassName("page-header")
                span {
                    className = ClassName("page-header-title")
                    +"Profile"
                }
            }

            div {
                className = ClassName("profile-page-scroll")
                div {
                    className = ClassName("profile-page-content")
                    div {
                        className = ClassName("profile-page-card")
                        div {
                            className = ClassName("profile-page-banner")
                            style = unsafeJso { background = profileBannerCss(props.pubkey).unsafeCast<Background>() }
                        }
                        div {
                            className = ClassName("profile-page-card-body")
                            div {
                                className = ClassName("profile-page-avatar-row")
                                div {
                                    className = ClassName("profile-page-avatar-ring")
                                    WebAvatar {
                                        url = metadata?.picture
                                        seed = props.pubkey
                                        this.name = name
                                        cls = "profile-page-avatar"
                                    }
                                }
                                if (vm.isSelf) {
                                    button {
                                        className = ClassName("btn-secondary")
                                        onClick = { props.onEditProfile() }
                                        +"Edit profile"
                                    }
                                }
                            }

                            div {
                                className = ClassName("profile-page-name-row")
                                h1 {
                                    className = ClassName("profile-page-name")
                                    +name
                                }
                                if (isAdminSomewhere) {
                                    span {
                                        className = ClassName("admin-chip")
                                        +"ADMIN"
                                    }
                                }
                            }
                            metadata?.nip05?.takeIf { it.isNotBlank() }?.let {
                                div {
                                    className = ClassName("profile-page-nip05")
                                    +it
                                }
                            }
                            metadata?.about?.takeIf { it.isNotBlank() }?.let {
                                p {
                                    className = ClassName("profile-page-about")
                                    +it
                                }
                            }
                            // Cycling identifier (prototype IdentifierField): npub /
                            // nprofile / link / hex / nip-05 with swap + copy.
                            IdentifierField {
                                pubkey = props.pubkey
                                nip05 = metadata?.nip05
                            }
                        }
                    }

                    div {
                        className = ClassName("profile-groups-label")
                        +((if (vm.isSelf) "Your groups" else "Groups in common") + " · ${groups.size}")
                    }
                    if (groups.isEmpty()) {
                        div {
                            className = ClassName("profile-groups-empty")
                            +"No groups in common."
                        }
                    } else {
                        groups.forEach { group ->
                            val groupName = group.meta.name ?: group.meta.id
                            button {
                                key = group.meta.id
                                className = ClassName("profile-group-row")
                                onClick = { props.onOpenGroup(GroupRoute(group.relayUrl, group.meta.id)) }
                                WebAvatar {
                                    url = group.meta.picture
                                    seed = group.meta.id
                                    this.name = groupName
                                    kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                    cls = "profile-group-avatar"
                                }
                                span {
                                    className = ClassName("profile-group-meta")
                                    span {
                                        className = ClassName("profile-group-name")
                                        +groupName
                                    }
                                    if (group.memberCount > 0) {
                                        span {
                                            className = ClassName("profile-group-members")
                                            +"${group.memberCount} members"
                                        }
                                    }
                                }
                                if (group.isAdmin) {
                                    span {
                                        className = ClassName("admin-chip")
                                        +"admin"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/** Banner from the user's avatar identity hue, darkened (prototype: u.color into dark). */
private fun profileBannerCss(seed: String): String {
    val g = AvatarGradients.user(seed)
    return "linear-gradient(120deg, hsl(${g.start.hue} 62% 40%), var(--color-background-dark))"
}
