package org.nostr.nostrord.web.screens

import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.mock.Mock
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.password

external interface SettingsScreenProps : Props {
    var onClose: () -> Unit
}

private val sections =
    listOf(
        "Profile",
        "Backup Keys",
        "Relays (NIP-65)",
        "Notifications",
        "Security",
        "Experimental",
    )

/**
 * Settings — layout-first React port of the Compose SettingsScreen: a full-screen
 * overlay with a section nav (Profile, Backup Keys, Relays, Notifications, Security,
 * Experimental) + Log Out, a content pane per section, and a close (ESC) button. Mock
 * data; saving/publishing/copying are stubbed.
 */
val SettingsScreen =
    FC<SettingsScreenProps> { props ->
        val (active, setActive) = useState { "Profile" }

        div {
            className = ClassName("settings-overlay")

            div { className = ClassName("settings-fill dark") }

            // Section nav
            div {
                className = ClassName("settings-sidebar")
                sections.forEach { section ->
                    div {
                        key = section
                        className = ClassName(if (section == active) "settings-nav-item active" else "settings-nav-item")
                        onClick = { setActive(section) }
                        +section
                    }
                }
                div { className = ClassName("settings-nav-divider") }
                div {
                    className = ClassName("settings-nav-item danger")
                    onClick = {
                        props.onClose()
                        launchApp { WebAuth.logout() }
                    }
                    +"Log Out"
                }
            }

            // Content
            div {
                className = ClassName("settings-content")
                when (active) {
                    "Profile" -> profilePanel()
                    "Backup Keys" -> backupPanel()
                    "Relays (NIP-65)" -> relaysPanel()
                    "Notifications" -> notificationsPanel()
                    "Security" -> securityPanel()
                    "Experimental" -> experimentalPanel()
                }
            }

            // Close button
            div {
                className = ClassName("settings-close-col")
                button {
                    className = ClassName("settings-close")
                    onClick = { props.onClose() }
                    span {
                        className = ClassName("settings-close-x")
                        +"✕"
                    }
                    span {
                        className = ClassName("settings-close-esc")
                        +"ESC"
                    }
                }
            }

            div { className = ClassName("settings-fill light") }
        }
    }

// ── Panels ─────────────────────────────────────────────────────────────────

private fun ChildrenBuilder.profilePanel() {
    // Avatar preview card
    div {
        className = ClassName("settings-card center")
        div {
            className = ClassName("avatar-tile settings-avatar avatar-fallback")
            +Mock.me.name.take(1).uppercase()
        }
        div {
            className = ClassName("settings-avatar-caption")
            +"Avatar Preview"
        }
    }
    // Form card
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-section-head")
            +"PROFILE INFORMATION"
        }
        settingsField("Name", "Your name", prefill = Mock.me.name)
        settingsTextarea("About", "Tell us about yourself")
        settingsField("Avatar URL", "https://example.com/avatar.jpg")
        settingsField("Banner URL", "https://example.com/banner.jpg")
        settingsField("Nostr Address (NIP-05)", "you@example.com")
        settingsField("Lightning Address", "you@walletofsatoshi.com")
        settingsField("Website", "https://example.com")
        div {
            className = ClassName("settings-form-actions")
            button {
                className = ClassName("settings-save")
                +"Save"
            }
        }
    }
}

private fun ChildrenBuilder.backupPanel() {
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("field-label")
            +"Public Key (npub)"
        }
        div {
            className = ClassName("settings-key")
            +Mock.me.npub
        }
        button {
            className = ClassName("settings-outline-btn")
            +"Copy Public Key"
        }
    }
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("field-label")
            +"Private Key (nsec)"
        }
        div {
            className = ClassName("settings-key danger")
            +"nsec1••••••••••••••••••••••••••••••••••••"
        }
        button {
            className = ClassName("settings-outline-btn danger")
            +"Copy Private Key"
        }
    }
    div {
        className = ClassName("settings-card warning")
        div {
            className = ClassName("settings-section-head")
            +"SECURITY TIPS"
        }
        div {
            className = ClassName("settings-tip")
            +"• Never share your private key (nsec) with anyone."
        }
        div {
            className = ClassName("settings-tip")
            +"• Store it in a password manager or write it down offline."
        }
        div {
            className = ClassName("settings-tip")
            +"• Anyone with your nsec has full control of your identity."
        }
    }
}

private fun ChildrenBuilder.relaysPanel() {
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-section-head")
            +"YOUR RELAYS"
        }
        listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
        ).forEach { url ->
            div {
                key = url
                className = ClassName("settings-relay-row")
                span {
                    className = ClassName("settings-relay-url")
                    +url
                }
                span {
                    className = ClassName("settings-relay-badge")
                    +"read/write"
                }
            }
        }
        div {
            className = ClassName("settings-form-actions")
            button {
                className = ClassName("settings-save")
                +"Publish"
            }
        }
    }
}

private fun ChildrenBuilder.notificationsPanel() {
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-toggle-row")
            div {
                className = ClassName("settings-toggle-text")
                div {
                    className = ClassName("settings-toggle-label")
                    +"Notification sound"
                }
                div {
                    className = ClassName("settings-toggle-desc")
                    +"Play a sound when a new message arrives."
                }
            }
            button {
                className = ClassName("settings-outline-btn")
                +"Test sound"
            }
        }
    }
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-section-head")
            +"DEFAULT FOR NEW GROUPS"
        }
        settingsRadioGroup(
            options = listOf(
                "All messages" to "Notify for every message.",
                "Mentions & replies only" to "Only notify when you are mentioned or replied to.",
                "Muted" to "Never notify.",
            ),
            selected = "All messages",
        )
    }
    div {
        className = ClassName("settings-card")
        settingsToggle(
            label = "Desktop notifications",
            description = "Show a system popup outside the app when a new message arrives.",
            checked = true,
        )
        div {
            className = ClassName("settings-perm")
            span {
                className = ClassName("settings-perm-status")
                +"Permission not granted yet."
            }
            button {
                className = ClassName("settings-outline-btn")
                +"Request permission"
            }
        }
    }
}

private fun ChildrenBuilder.securityPanel() {
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-section-head")
            +"APP PASSPHRASE"
        }
        div {
            className = ClassName("settings-status-line")
            +"No passphrase set"
        }
        settingsField("Current passphrase", "Current passphrase", password = true)
        settingsField("New passphrase", "New passphrase", password = true)
        settingsField("Confirm new passphrase", "Confirm new passphrase", password = true)
        div {
            className = ClassName("field-hint")
            +"At least 8 characters. Must match."
        }
        div {
            className = ClassName("settings-form-actions")
            button {
                className = ClassName("settings-save")
                +"Change passphrase"
            }
        }
    }
}

private fun ChildrenBuilder.experimentalPanel() {
    div {
        className = ClassName("settings-card")
        div {
            className = ClassName("settings-section-head")
            +"DRAFT PROTOCOL FEATURES"
        }
        settingsToggle(
            label = "NIP-29 Subgroups (draft)",
            description = "Enable nested subgroups. This is a draft protocol feature and may change.",
            checked = false,
        )
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

private fun ChildrenBuilder.settingsField(
    label: String,
    placeholder: String,
    prefill: String = "",
    password: Boolean = false,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        input {
            className = ClassName("modal-input")
            this.placeholder = placeholder
            if (prefill.isNotEmpty()) defaultValue = prefill
            if (password) type = InputType.password
        }
    }
}

private fun ChildrenBuilder.settingsTextarea(label: String, placeholder: String) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        textarea {
            className = ClassName("modal-textarea")
            this.placeholder = placeholder
            rows = 3
        }
    }
}

private fun ChildrenBuilder.settingsToggle(label: String, description: String, checked: Boolean) {
    div {
        className = ClassName("settings-toggle-row")
        div {
            className = ClassName("settings-toggle-text")
            div {
                className = ClassName("settings-toggle-label")
                +label
            }
            div {
                className = ClassName("settings-toggle-desc")
                +description
            }
        }
        div {
            className = ClassName(if (checked) "switch on" else "switch")
            div { className = ClassName("switch-thumb") }
        }
    }
}

private fun ChildrenBuilder.settingsRadioGroup(options: List<Pair<String, String>>, selected: String) {
    options.forEach { (label, desc) ->
        div {
            className = ClassName("settings-radio-row")
            div {
                className = ClassName(if (label == selected) "settings-radio on" else "settings-radio")
                div { className = ClassName("settings-radio-dot") }
            }
            div {
                className = ClassName("settings-toggle-text")
                div {
                    className = ClassName("settings-toggle-label")
                    +label
                }
                div {
                    className = ClassName("settings-toggle-desc")
                    +desc
                }
            }
        }
    }
}
