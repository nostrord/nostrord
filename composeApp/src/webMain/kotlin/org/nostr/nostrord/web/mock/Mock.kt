package org.nostr.nostrord.web.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Layout-first mock data — lets every screen render standalone, with no API/login. */

data class MockAccount(
    val pubkey: String,
    val name: String,
    val npub: String,
    val authMethod: String,
    val picture: String?,
    val active: Boolean = false,
)

data class MockRelay(val url: String, val name: String, val icon: String?, val unread: Int = 0)

data class MockGroup(val id: String, val name: String, val about: String?, val picture: String?, val unread: Int)

/** A chat message. [firstInGroup] = false hides the avatar/name (consecutive same-author). */
data class MockMessage(
    val id: String,
    val author: String,
    val time: String,
    val content: String,
    val firstInGroup: Boolean,
    val admin: Boolean = false,
)

data class MockMember(val name: String, val admin: Boolean, val online: Boolean)

/** [type] is one of "reply", "mention", "reaction", "message". */
data class MockNotification(
    val id: String,
    val actor: String,
    val type: String,
    val preview: String,
    val group: String,
    val time: String,
    val read: Boolean,
)

object Mock {
    val me =
        MockAccount(
            pubkey = "3bf0c2…7a9d4e",
            name = "Anjhc",
            npub = "npub1f27g79lrpey9hz5q3w8m4xk2v",
            authMethod = "Bunker (NIP-46)",
            picture = null,
            active = true,
        )

    val accounts = listOf(me)

    val relays =
        listOf(
            MockRelay("wss://groups.0xchat.com", "0xchat", null, unread = 3),
            MockRelay("wss://relay.groups.nip29.com", "nip29", null),
            MockRelay("wss://groups.fiatjaf.com", "fiatjaf", null),
        )

    val activeRelay = relays.first()

    val groups =
        listOf(
            MockGroup("g1", "Nostrord Dev", "Building the NIP-29 client", null, 3),
            MockGroup("g2", "Design", "UI / UX discussion", null, 0),
            MockGroup("g3", "Random", "Off-topic chatter", null, 12),
            MockGroup("g4", "Announcements", "Official updates", null, 0),
            MockGroup("g5", "Support", "Help and questions", null, 1),
        )

    val sampleDate = "May 23, 2026"

    val sampleMessages =
        listOf(
            MockMessage("m1", "Anjhc", "10:02", "Hey everyone 👋 welcome to the dev group!", true, admin = true),
            MockMessage("m2", "Anjhc", "10:02", "We just shipped the new web frontend rebuild.", false, admin = true),
            MockMessage("m3", "fiatjaf", "10:15", "Nice! Is it React now?", true),
            MockMessage("m4", "fiatjaf", "10:16", "The Compose canvas felt rough on mobile web.", false),
            MockMessage("m5", "hodlbod", "10:31", "kotlin-react via wrappers, reusing the same shared logic 🔥", true),
            MockMessage("m6", "Anjhc", "10:33", "Exactly. Layout-first, then we wire the relays.", true, admin = true),
            MockMessage("m7", "pablof7z", "10:40", "Looking forward to testing it out.", true),
        )

    val sampleMembers =
        listOf(
            MockMember("Anjhc", admin = true, online = true),
            MockMember("fiatjaf", admin = false, online = true),
            MockMember("hodlbod", admin = false, online = true),
            MockMember("pablof7z", admin = false, online = false),
            MockMember("jack", admin = false, online = false),
            MockMember("vitorpamplona", admin = true, online = false),
        )

    val sampleInviteCodes = listOf("a1b2c3d4e5", "ff00ee11aa22")

    val sampleJoinRequests = listOf("satoshi", "hal", "luke")

    val sampleNotifications =
        listOf(
            MockNotification("n1", "fiatjaf", "mention", "Hey @anjhc, can you review the relay PR?", "Nostrord Dev", "2m", read = false),
            MockNotification("n2", "hodlbod", "reply", "I think the StateFlow bridge is clean.", "Nostrord Dev", "15m", read = false),
            MockNotification("n3", "pablof7z", "reaction", "🔥", "Design", "1h", read = true),
            MockNotification("n4", "jack", "message", "gm everyone ☀️", "Random", "3h", read = true),
            MockNotification("n5", "vitorpamplona", "mention", "@anjhc nice work on the web rebuild", "Nostrord Dev", "5h", read = true),
        )
}

private val mockLoggedIn = MutableStateFlow(false)

val mockSession: StateFlow<Boolean> = mockLoggedIn.asStateFlow()

fun mockLogin() {
    mockLoggedIn.value = true
}

fun mockLogout() {
    mockLoggedIn.value = false
    mockHasRelays.value = false
}

/**
 * Whether the logged-in account has any relay yet. A fresh account starts with none, so
 * login lands on the onboarding screen until the first relay is added — mirrors the real
 * first-run flow (login → onboarding → shell).
 */
private val mockHasRelays = MutableStateFlow(false)

val mockRelaysState: StateFlow<Boolean> = mockHasRelays.asStateFlow()

fun mockAddRelay() {
    mockHasRelays.value = true
}
