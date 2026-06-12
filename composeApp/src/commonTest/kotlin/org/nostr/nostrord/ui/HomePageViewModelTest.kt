package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomePageViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun meta(
        id: String,
        name: String,
        about: String? = null,
    ) = GroupMetadata(id = id, name = name, about = about, picture = null, isPublic = true, isOpen = true)

    @Test
    fun `myGroups joins kind10009 ids with metadata across relays`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf(
                "wss://a" to listOf(meta("g1", "Alpha"), meta("g2", "Beta")),
                "wss://b" to listOf(meta("g3", "Gamma")),
            )
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://a" to setOf("g1"),
                "wss://b" to setOf("g3", "unknown"),
            )
        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        val ids = vm.myGroups.value.map { it.meta.id }
        assertEquals(listOf("g1", "g3", "unknown"), ids)
        // Each entry carries its hosting relay, for navigation.
        assertEquals(listOf("wss://a", "wss://b", "wss://b"), vm.myGroups.value.map { it.relayUrl })
        // Unknown ids fall back to a bare-id placeholder until the kind:39000 arrives.
        assertEquals(null, vm.myGroups.value.last().meta.name)
    }

    @Test
    fun `query filters by name and description`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value =
            mapOf("wss://a" to listOf(meta("g1", "Alpha", "cats"), meta("g2", "Beta", "dogs")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1", "g2"))
        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setQuery("dogs")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("g2"), vm.myGroups.value.map { it.meta.id })

        vm.setQuery("ALPHA")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("g1"), vm.myGroups.value.map { it.meta.id })
    }

    @Test
    fun `notificationUnread counts only unread entries`() = runTest {
        fun entry(
            id: String,
            read: Boolean,
        ) = NotificationEntry(
            id = id,
            type = NotificationType.MENTION,
            groupId = "g1",
            relayUrl = "wss://a",
            actorPubkey = "pk",
            createdAt = 1L,
            preview = "",
            read = read,
        )

        val store = NotificationHistoryStore()
        val vm = HomePageViewModel(FakeNostrRepository(), store)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.notificationUnread.value)

        store.add(entry("n1", read = false))
        store.add(entry("n2", read = false))
        store.add(entry("n3", read = true))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.notificationUnread.value)

        store.markRead("n1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.notificationUnread.value)
    }
}
