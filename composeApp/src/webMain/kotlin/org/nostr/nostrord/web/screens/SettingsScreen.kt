package org.nostr.nostrord.web.screens

import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.notifications.playNotificationSound
import org.nostr.nostrord.settings.NotificationLevel
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface SettingsScreenProps : Props {
    var onClose: () -> Unit
}

private val sections =
    listOf("Profile", "Backup Keys", "Relays (NIP-65)", "Notifications", "Security", "Experimental")

/**
 * Settings — real port of the Compose SettingsScreen: a full-screen overlay with a section
 * nav + Log Out, a content pane per section, and the close/ESC button. Panels read/write
 * the real repository, account store, notification settings and feature flags.
 */
val SettingsScreen =
    FC<SettingsScreenProps> { props ->
        val (active, setActive) = useState { "Profile" }
        // Mobile: false = section list, true = selected panel (with a back button). Ignored on desktop.
        val (mobilePanel, setMobilePanel) = useState { false }

        div {
            className = ClassName(if (mobilePanel) "settings-overlay show-panel" else "settings-overlay")

            div { className = ClassName("settings-fill dark") }

            div {
                className = ClassName("settings-sidebar")
                // Mobile-only list header with title + round close (CSS hides it on desktop).
                div {
                    className = ClassName("settings-mobile-header")
                    span {
                        className = ClassName("settings-mobile-title")
                        +"Settings"
                    }
                    button {
                        className = ClassName("settings-mobile-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }
                sections.forEach { section ->
                    div {
                        key = section
                        className = ClassName(if (section == active) "settings-nav-item active" else "settings-nav-item")
                        onClick = {
                            setActive(section)
                            setMobilePanel(true)
                        }
                        span {
                            className = ClassName("settings-nav-label")
                            +section
                        }
                        span {
                            className = ClassName("settings-nav-chevron")
                            icon(Ic.ChevronRight)
                        }
                    }
                }
                div { className = ClassName("settings-nav-divider") }
                div {
                    className = ClassName("settings-nav-item danger")
                    onClick = {
                        props.onClose()
                        launchApp { AppModule.nostrRepository.logout() }
                    }
                    span {
                        className = ClassName("settings-nav-label")
                        +"Log Out"
                    }
                }
            }

            div {
                className = ClassName("settings-content")
                // Mobile-only back row (CSS hides it on desktop) — returns to the section list.
                button {
                    className = ClassName("settings-back")
                    onClick = { setMobilePanel(false) }
                    icon(Ic.ArrowBack)
                    +active
                }
                // Desktop-only panel title with a divider (mirrors native; mobile uses the back row).
                div {
                    className = ClassName("settings-content-title")
                    +active
                }
                when (active) {
                    "Profile" -> ProfilePanel()
                    "Backup Keys" -> BackupPanel()
                    "Relays (NIP-65)" -> RelaysPanel()
                    "Notifications" -> NotificationsPanel()
                    "Security" -> SecurityPanel()
                    "Experimental" -> ExperimentalPanel()
                }
            }

            div {
                className = ClassName("settings-close-col")
                button {
                    className = ClassName("settings-close")
                    onClick = { props.onClose() }
                    span {
                        className = ClassName("settings-close-x")
                        icon(Ic.Close)
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

// ── Profile ──────────────────────────────────────────────────────────────────

private val ProfilePanel =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val pubkey = repo.getPublicKey()
        val userMetadata = useStateFlow(repo.userMetadata)
        val meta = pubkey?.let { userMetadata[it] }

        val (name, setName) = useState { meta?.displayName ?: meta?.name ?: "" }
        val (about, setAbout) = useState { meta?.about ?: "" }
        val (picture, setPicture) = useState { meta?.picture ?: "" }
        val (banner, setBanner) = useState { meta?.banner ?: "" }
        val (nip05, setNip05) = useState { meta?.nip05 ?: "" }
        val (lud16, setLud16) = useState { meta?.lud16 ?: "" }
        val (website, setWebsite) = useState { meta?.website ?: "" }
        val (busy, setBusy) = useState { false }
        val (saved, setSaved) = useState { false }

        div {
            className = ClassName("settings-card center")
            WebAvatar {
                url = picture.ifBlank { null }
                seed = pubkey
                this.name = name.ifBlank { "U" }
                cls = "settings-avatar"
            }
            div {
                className = ClassName("settings-avatar-caption")
                +"Avatar Preview"
            }
        }
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"PROFILE INFORMATION"
            }
            settingsField("Name", "Your name", name) {
                setName(it)
                setSaved(false)
            }
            settingsTextarea("About", "Tell us about yourself", about) { setAbout(it) }
            settingsUploadField("Avatar URL", "https://example.com/avatar.jpg", picture) { setPicture(it) }
            settingsUploadField("Banner URL", "https://example.com/banner.jpg", banner) { setBanner(it) }
            settingsField("Nostr Address (NIP-05)", "you@example.com", nip05) { setNip05(it) }
            settingsField("Lightning Address", "you@walletofsatoshi.com", lud16) { setLud16(it) }
            settingsField("Website", "https://example.com", website) { setWebsite(it) }
            if (saved) {
                div {
                    className = ClassName("settings-status-line")
                    +"Profile updated successfully"
                }
            }
            div {
                className = ClassName("settings-form-actions")
                button {
                    className = ClassName("settings-save")
                    disabled = busy
                    onClick = {
                        setBusy(true)
                        launchApp {
                            val result =
                                repo.updateProfileMetadata(
                                    displayName = name.trim().ifBlank { null },
                                    name = name.trim().ifBlank { null },
                                    about = about.trim().ifBlank { null },
                                    picture = picture.trim().ifBlank { null },
                                    banner = banner.trim().ifBlank { null },
                                    nip05 = nip05.trim().ifBlank { null },
                                    lud16 = lud16.trim().ifBlank { null },
                                    website = website.trim().ifBlank { null },
                                )
                            setBusy(false)
                            if (result is Result.Success) setSaved(true)
                        }
                    }
                    +(if (busy) "Saving…" else "Save")
                }
            }
        }
    }

// ── Backup keys ──────────────────────────────────────────────────────────────

private val BackupPanel =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val pubkey = repo.getPublicKey()
        val npub = pubkey?.let { Nip19.encodeNpub(it) } ?: ""
        val isLocal = AppModule.accountStore.active?.authMethod == AuthMethod.LOCAL
        val (revealed, setRevealed) = useState { false }
        val nsec =
            if (revealed && isLocal) {
                repo.getPrivateKey()?.let { Nip19.encodeNsec(it) } ?: ""
            } else {
                "nsec1••••••••••••••••••••••••••••••••••••"
            }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("field-label")
                +"Public Key (npub)"
            }
            div {
                className = ClassName("settings-key")
                +npub
            }
            button {
                className = ClassName("settings-outline-btn")
                onClick = { copyText(npub) }
                +"Copy Public Key"
            }
        }
        if (isLocal) {
            div {
                className = ClassName("settings-card")
                div {
                    className = ClassName("field-label")
                    +"Private Key (nsec)"
                }
                div {
                    className = ClassName("settings-key danger")
                    +nsec
                }
                button {
                    className = ClassName("settings-outline-btn danger")
                    onClick = {
                        if (!revealed) {
                            setRevealed(true)
                        } else {
                            repo.getPrivateKey()?.let { copyText(Nip19.encodeNsec(it)) }
                        }
                    }
                    +(if (revealed) "Copy Private Key" else "Reveal Private Key")
                }
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

// ── Relays (NIP-65) ──────────────────────────────────────────────────────────

private fun defaultRelays(): List<Nip65Relay> = RelayListManager.DEFAULT_FALLBACK_RELAYS.map { Nip65Relay(it) }

private val RelaysPanel =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val loaded = useStateFlow(repo.userRelayList)
        val usingDefaults = loaded.isEmpty()

        // Editable copy; reseeds when the server list first populates (or clears).
        val (relays, setRelays) = useState { if (loaded.isEmpty()) defaultRelays() else loaded }
        useEffect(loaded.isEmpty()) {
            setRelays(if (loaded.isEmpty()) defaultRelays() else loaded)
        }

        val (newUrl, setNewUrl) = useState { "" }
        val (newRead, setNewRead) = useState { true }
        val (newWrite, setNewWrite) = useState { true }
        val (busy, setBusy) = useState { false }
        val (status, setStatus) = useState<String?> { null }

        // Same canAdd contract as native RelayNip65PanelContent: valid URL,
        // at least one of R/W marked, and not already in the list. Drives both
        // the Enter-key shortcut and the Add button's disabled state below.
        val normalizedNewUrl = newUrl.trim().toRelayUrl()
        val canAddRelay =
            isValidRelayUrl(normalizedNewUrl) &&
                (newRead || newWrite) &&
                relays.none { it.url == normalizedNewUrl }

        fun addRelay() {
            if (!canAddRelay) return
            setRelays(relays + Nip65Relay(normalizedNewUrl, newRead, newWrite))
            setNewUrl("")
        }

        // Info card
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-info-text")
                +(
                    "NIP-65 relay list (kind 10002) is where other clients find your profile and your joined " +
                        "groups list (kind 10009). Write relays are where you publish; read relays are for " +
                        "cross-network discoverability. Group messages are separate — they live on each group's relay."
                    )
            }
        }

        // Using-defaults warning
        if (usingDefaults) {
            div {
                className = ClassName("settings-warn-card")
                icon(Ic.Warning, "ico settings-warn-ico")
                div {
                    className = ClassName("settings-warn-text")
                    div {
                        className = ClassName("settings-warn-title")
                        +"Using default relays"
                    }
                    div {
                        className = ClassName("settings-warn-body")
                        +"No relay list (kind 10002) was found for this account. Publish your relay list so others can find your profile and groups."
                    }
                }
            }
        }

        // Relay list + add
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"YOUR RELAYS"
            }
            relays.forEachIndexed { i, relay ->
                div {
                    key = relay.url
                    className = ClassName("relay-row")
                    span {
                        className = ClassName("relay-row-url")
                        +relay.url.removePrefix("wss://").removePrefix("ws://")
                    }
                    relayChip("R", relay.read) {
                        setRelays(relays.mapIndexed { j, r -> if (j == i) r.copy(read = !r.read) else r })
                    }
                    relayChip("W", relay.write) {
                        setRelays(relays.mapIndexed { j, r -> if (j == i) r.copy(write = !r.write) else r })
                    }
                    button {
                        className = ClassName("relay-row-remove")
                        onClick = { setRelays(relays.filterIndexed { j, _ -> j != i }) }
                        icon(Ic.Close)
                    }
                }
            }
            if (relays.none { it.read }) {
                div {
                    className = ClassName("relay-warn")
                    +"No read relay — cross-network discoverability will be limited."
                }
            }
            if (relays.none { it.write }) {
                div {
                    className = ClassName("relay-warn")
                    +"No write relay — your profile and joined groups list won't be discoverable."
                }
            }

            div {
                className = ClassName("settings-section-head relay-add-head")
                +"ADD RELAY"
            }
            input {
                className = ClassName("modal-input")
                placeholder = "relay.example.com"
                value = newUrl
                onChange = { event -> setNewUrl(event.currentTarget.value) }
                onKeyDown = { event ->
                    if (event.key == "Enter") {
                        event.preventDefault()
                        addRelay()
                    }
                }
            }
            div {
                className = ClassName("relay-add-row")
                label {
                    className = ClassName("relay-check")
                    input {
                        type = InputType.checkbox
                        checked = newRead
                        onChange = { event -> setNewRead(event.currentTarget.checked) }
                    }
                    +"Read"
                }
                label {
                    className = ClassName("relay-check")
                    input {
                        type = InputType.checkbox
                        checked = newWrite
                        onChange = { event -> setNewWrite(event.currentTarget.checked) }
                    }
                    +"Write"
                }
                button {
                    className = ClassName("relay-add-btn")
                    disabled = !canAddRelay
                    onClick = { addRelay() }
                    icon(Ic.Add)
                    +"Add"
                }
            }
        }

        // Save & Publish
        div {
            className = ClassName("relay-save-row")
            status?.let {
                span {
                    className = ClassName("relay-save-status")
                    +it
                }
            }
            button {
                className = ClassName("settings-save")
                disabled = busy
                onClick = {
                    setBusy(true)
                    setStatus(null)
                    launchApp {
                        val result = repo.publishRelayList(relays)
                        setBusy(false)
                        setStatus(if (result is Result.Success) "Relay list published" else "Failed to publish")
                    }
                }
                +(if (busy) "Publishing…" else "Save & Publish")
            }
        }
    }

private fun react.ChildrenBuilder.relayChip(label: String, active: Boolean, onToggle: () -> Unit) {
    button {
        className = ClassName(if (active) "relay-chip on" else "relay-chip")
        onClick = { onToggle() }
        +label
    }
}

// ── Notifications ────────────────────────────────────────────────────────────

private val NotificationsPanel =
    FC<Props> {
        val settings = AppModule.notificationSettings
        val service = AppModule.notificationService
        val soundEnabled = useStateFlow(settings.soundEnabled)
        val systemEnabled = useStateFlow(settings.systemNotificationsEnabled)
        val defaultLevel = useStateFlow(settings.defaultLevel)
        val permission = useStateFlow(service.permission)

        div {
            className = ClassName("settings-card")
            settingsToggle("Notification sound", "Play a sound when a new message arrives.", soundEnabled) {
                settings.setSoundEnabled(!soundEnabled)
            }
            if (soundEnabled) {
                button {
                    className = ClassName("settings-test-sound")
                    onClick = { playNotificationSound() }
                    +"Test sound"
                }
            }
            // Desktop notifications live in the same box, separated by a divider (native layout).
            div { className = ClassName("settings-card-divider") }
            settingsToggle(
                "Desktop notifications",
                "Show a system popup outside the app when a new message arrives.",
                systemEnabled,
            ) { settings.setSystemNotificationsEnabled(!systemEnabled) }
            if (service.isSupported()) {
                div {
                    className = ClassName("settings-perm")
                    span {
                        className = ClassName("settings-perm-status")
                        +when (permission) {
                            NotificationPermission.Granted -> "Permission granted"
                            NotificationPermission.Denied -> "Permission denied in the browser."
                            else -> "Permission not granted yet."
                        }
                    }
                    if (permission != NotificationPermission.Granted) {
                        button {
                            className = ClassName("settings-outline-btn")
                            onClick = { service.requestPermission() }
                            +"Request permission"
                        }
                    }
                }
            }
        }
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"DEFAULT FOR NEW GROUPS"
            }
            div {
                className = ClassName("settings-section-desc")
                +"Applied to groups you haven't set individually. Change a single group from its info screen."
            }
            levelRadio("All messages", "Notify for every message.", defaultLevel == NotificationLevel.ALL) {
                settings.setDefaultLevel(NotificationLevel.ALL)
            }
            levelRadio(
                "Mentions & replies only",
                "Only notify when you are mentioned or replied to.",
                defaultLevel == NotificationLevel.MENTIONS_REPLIES,
            ) { settings.setDefaultLevel(NotificationLevel.MENTIONS_REPLIES) }
            levelRadio("Muted", "Never notify.", defaultLevel == NotificationLevel.MUTED) {
                settings.setDefaultLevel(NotificationLevel.MUTED)
            }
        }
    }

// ── Security ─────────────────────────────────────────────────────────────────

private val SecurityPanel =
    FC<Props> {
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"APP SECURITY"
            }
            div {
                className = ClassName("settings-status-line")
                +"No app passphrase on the web — your key is managed by the browser. Use Backup Keys to save it."
            }
        }
    }

// ── Experimental ─────────────────────────────────────────────────────────────

private val ExperimentalPanel =
    FC<Props> {
        val flags = AppModule.featureFlags
        val subgroups = useStateFlow(flags.subgroupsEnabled)
        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("settings-section-head")
                +"DRAFT PROTOCOL FEATURES"
            }
            settingsToggle(
                "NIP-29 Subgroups (draft)",
                "Enable nested subgroups. This is a draft protocol feature and may change.",
                subgroups,
            ) { flags.setSubgroupsEnabled(!subgroups) }
        }
    }

// ── Shared bits ──────────────────────────────────────────────────────────────

private fun copyText(text: String) {
    val clip = kotlinx.browser.window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

private fun react.ChildrenBuilder.settingsField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
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
            this.value = value
            this.onChange = { event -> onChange(event.currentTarget.value) }
        }
    }
}

private fun react.ChildrenBuilder.settingsUploadField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("settings-field")
        div {
            className = ClassName("field-label")
            +label
        }
        div {
            className = ClassName("upload-field")
            input {
                className = ClassName("modal-input flush")
                this.placeholder = placeholder
                this.value = value
                this.onChange = { event -> onChange(event.currentTarget.value) }
            }
            UploadButton {
                cls = "upload-btn"
                icon = Ic.Upload
                onUploaded = onChange
            }
        }
    }
}

private fun react.ChildrenBuilder.settingsTextarea(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
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
            this.value = value
            this.onChange = { event -> onChange(event.currentTarget.value) }
        }
    }
}

private fun react.ChildrenBuilder.settingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
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
            onClick = { onToggle() }
            div { className = ClassName("switch-thumb") }
        }
    }
}

private fun react.ChildrenBuilder.levelRadio(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    div {
        className = ClassName("settings-radio-row")
        onClick = { onSelect() }
        div {
            className = ClassName(if (selected) "settings-radio on" else "settings-radio")
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
                +description
            }
        }
    }
}
