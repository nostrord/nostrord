package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.navigation.navigate
import org.nostr.nostrord.web.upload.UploadButton
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

private fun ChildrenBuilder.labeledInput(
    labelText: String,
    fieldValue: String,
    fieldDisabled: Boolean,
    onValue: (String) -> Unit,
) {
    label {
        className = ClassName("field-label")
        +labelText
    }
    input {
        value = fieldValue
        disabled = fieldDisabled
        onChange = { event -> onValue(event.currentTarget.value) }
    }
}

private fun ChildrenBuilder.toggleRow(
    labelText: String,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    label {
        className = ClassName("toggle-row")
        input {
            type = InputType.checkbox
            checked = isChecked
            onChange = { event -> onToggle(event.currentTarget.checked) }
        }
        span { +labelText }
    }
}

/**
 * Profile view/edit — prefills the signed-in user's kind:0 metadata and saves via the
 * shared `nostrRepository.updateProfileMetadata` (bypassing the Compose-only ViewModel,
 * which lives in uiComposeMain). Also links to backup and notifications.
 */
val ProfileScreen =
    FC<Props> {
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val meta = activeId?.let { userMetadata[it] }

        val (displayName, setDisplayName) = useState { meta?.displayName ?: "" }
        val (name, setName) = useState { meta?.name ?: "" }
        val (about, setAbout) = useState { meta?.about ?: "" }
        val (picture, setPicture) = useState { meta?.picture ?: "" }
        val (banner, setBanner) = useState { meta?.banner ?: "" }
        val (nip05, setNip05) = useState { meta?.nip05 ?: "" }
        val (lud16, setLud16) = useState { meta?.lud16 ?: "" }
        val (website, setWebsite) = useState { meta?.website ?: "" }
        val (status, setStatus) = useState<String?> { null }
        val (busy, setBusy) = useState { false }

        val soundEnabled = useStateFlow(AppModule.notificationSettings.soundEnabled)
        val systemNotifications = useStateFlow(AppModule.notificationSettings.systemNotificationsEnabled)
        val subgroupsEnabled = useStateFlow(AppModule.featureFlags.subgroupsEnabled)
        val notifPermission = useStateFlow(AppModule.notificationService.permission)
        val userRelayList = useStateFlow(AppModule.nostrRepository.userRelayList)
        val (newNip65, setNewNip65) = useState { "" }

        fun save() {
            setBusy(true)
            setStatus(null)
            launchApp {
                val result =
                    AppModule.nostrRepository.updateProfileMetadata(
                        displayName.ifBlank { null },
                        name.ifBlank { null },
                        about.ifBlank { null },
                        picture.ifBlank { null },
                        banner.ifBlank { null },
                        nip05.ifBlank { null },
                        lud16.ifBlank { null },
                        website.ifBlank { null },
                    )
                setBusy(false)
                setStatus(if (result is Result.Error) "Failed to save profile." else "Profile saved.")
            }
        }

        div {
            className = ClassName("app-shell")
            h1 { +"Profile" }

            labeledInput("Display name", displayName, busy) { setDisplayName(it) }
            labeledInput("Username (name)", name, busy) { setName(it) }

            label {
                className = ClassName("field-label")
                +"About"
            }
            textarea {
                value = about
                disabled = busy
                rows = 3
                onChange = { event -> setAbout(event.currentTarget.value) }
            }

            labeledInput("Picture URL", picture, busy) { setPicture(it) }
            UploadButton {
                label = "Upload image"
                accept = "image/*"
                onUploaded = { setPicture(it) }
            }
            labeledInput("Banner URL", banner, busy) { setBanner(it) }
            UploadButton {
                label = "Upload banner"
                accept = "image/*"
                onUploaded = { setBanner(it) }
            }
            labeledInput("NIP-05", nip05, busy) { setNip05(it) }
            labeledInput("Lightning address (lud16)", lud16, busy) { setLud16(it) }
            labeledInput("Website", website, busy) { setWebsite(it) }

            status?.let { message ->
                p {
                    className = ClassName("muted")
                    +message
                }
            }

            div {
                className = ClassName("row-actions")
                button {
                    disabled = busy
                    onClick = { save() }
                    +(if (busy) "Saving…" else "Save profile")
                }
            }

            div {
                className = ClassName("row-actions")
                button {
                    className = ClassName("secondary")
                    onClick = { navigate(Screen.BackupPrivateKey) }
                    +"Backup key"
                }
                button {
                    className = ClassName("secondary")
                    onClick = { navigate(Screen.Notifications) }
                    +"Notifications"
                }
            }

            h2 { +"Preferences" }
            toggleRow("Notification sound", soundEnabled) {
                AppModule.notificationSettings.setSoundEnabled(it)
            }
            toggleRow("System notifications", systemNotifications) {
                AppModule.notificationSettings.setSystemNotificationsEnabled(it)
            }
            toggleRow("Subgroups (experimental)", subgroupsEnabled) {
                AppModule.featureFlags.setSubgroupsEnabled(it)
            }
            if (AppModule.notificationService.isSupported() && notifPermission != NotificationPermission.Granted) {
                div {
                    className = ClassName("row-actions")
                    button {
                        className = ClassName("secondary")
                        onClick = { AppModule.notificationService.requestPermission() }
                        +"Enable browser notifications"
                    }
                }
            }

            h2 { +"Relays (NIP-65)" }
            p {
                className = ClassName("muted")
                +"Where your profile and activity are published and read."
            }
            userRelayList.forEach { relay ->
                div {
                    key = relay.url
                    className = ClassName("nip65-row")
                    span {
                        className = ClassName("nip65-url")
                        +relay.url.removePrefix("wss://").removePrefix("ws://")
                    }
                    label {
                        className = ClassName("nip65-toggle")
                        input {
                            type = InputType.checkbox
                            checked = relay.read
                            onChange = { event ->
                                val read = event.currentTarget.checked
                                launchApp {
                                    AppModule.nostrRepository.publishRelayList(
                                        userRelayList.map { if (it.url == relay.url) it.copy(read = read) else it },
                                    )
                                }
                            }
                        }
                        span { +"R" }
                    }
                    label {
                        className = ClassName("nip65-toggle")
                        input {
                            type = InputType.checkbox
                            checked = relay.write
                            onChange = { event ->
                                val write = event.currentTarget.checked
                                launchApp {
                                    AppModule.nostrRepository.publishRelayList(
                                        userRelayList.map { if (it.url == relay.url) it.copy(write = write) else it },
                                    )
                                }
                            }
                        }
                        span { +"W" }
                    }
                    button {
                        className = ClassName("nip65-remove")
                        onClick = {
                            launchApp {
                                AppModule.nostrRepository.publishRelayList(userRelayList.filter { it.url != relay.url })
                            }
                        }
                        +"×"
                    }
                }
            }
            div {
                className = ClassName("nip65-add")
                input {
                    className = ClassName("modal-input")
                    placeholder = "wss://relay…"
                    value = newNip65
                    onChange = { event -> setNewNip65(event.currentTarget.value) }
                }
                button {
                    className = ClassName("secondary")
                    disabled = newNip65.isBlank()
                    onClick = {
                        val url = newNip65.trim()
                        if (url.isNotBlank()) {
                            launchApp { AppModule.nostrRepository.publishRelayList(userRelayList + Nip65Relay(url)) }
                            setNewNip65("")
                        }
                    }
                    +"Add"
                }
            }
        }
    }
