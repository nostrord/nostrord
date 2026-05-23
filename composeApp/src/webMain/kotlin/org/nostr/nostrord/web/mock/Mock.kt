package org.nostr.nostrord.web.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Layout-first mock data — lets every screen render standalone, with no API/login. */

data class MockUser(val pubkey: String, val name: String, val handle: String?, val picture: String?)

data class MockRelay(val url: String, val name: String, val icon: String?, val unread: Int = 0)

data class MockGroup(val id: String, val name: String, val about: String?, val picture: String?, val unread: Int)

object Mock {
    val me = MockUser("3bf0c2…7a9d4e", "satoshi", "satoshi@nostrord.com", null)

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
}

private val mockLoggedIn = MutableStateFlow(false)

val mockSession: StateFlow<Boolean> = mockLoggedIn.asStateFlow()

fun mockLogin() {
    mockLoggedIn.value = true
}

fun mockLogout() {
    mockLoggedIn.value = false
}
