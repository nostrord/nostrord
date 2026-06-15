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
    fun `friends lists following enriched with metadata sorted by name`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("pkB", "pkA")
        fake._userMetadata.value =
            mapOf(
                "pkA" to org.nostr.nostrord.network.UserMetadata(
                    pubkey = "pkA",
                    name = "zoe",
                    displayName = "Zoe",
                    picture = null,
                    about = null,
                    nip05 = null,
                ),
                "pkB" to org.nostr.nostrord.network.UserMetadata(
                    pubkey = "pkB",
                    name = "amy",
                    displayName = "Amy",
                    picture = null,
                    about = null,
                    nip05 = null,
                ),
            )
        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        // Sorted by display name: Amy before Zoe, each carrying its metadata.
        assertEquals(listOf("pkB", "pkA"), vm.friends.value.map { it.pubkey })
        assertEquals(listOf("Amy", "Zoe"), vm.friends.value.map { it.metadata?.displayName })
    }

    @Test
    fun `friendsGroups lists friends' groups excluding mine ranked by overlap`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice", "bob")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
                "bob" to listOf(org.nostr.nostrord.network.UserGroupRef("wss://a", "g1")),
            )
        // I'm already in g2, so it must be excluded from discovery.
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Group One")))

        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        val fgs = vm.friendsGroups.value
        assertEquals(listOf("g1"), fgs.map { it.meta.id })
        assertEquals(setOf("alice", "bob"), fgs.single().people.map { it.pubkey }.toSet())
        assertEquals("Group One", fgs.single().meta.name)
    }

    @Test
    fun `friends' group metadata is fetched deduped by relay and group`() = runTest {
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice", "bob")
        fake._userGroupLists.value =
            mapOf(
                // alice and bob both list g1 on the same relay -> one fetch; g2 is mine.
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
                "bob" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://b", "g3"),
                    ),
            )
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))

        HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        val byRelay =
            fake.fetchGroupPreviewsCalls
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, sets) -> sets.flatten().toSet() }
        // g1 once on wss://a (deduped across alice+bob), g3 on wss://b, g2 excluded (mine).
        assertEquals(setOf("g1"), byRelay.getValue("wss://a"))
        assertEquals(setOf("g3"), byRelay.getValue("wss://b"))
    }

    @Test
    fun `myGroups people preview shows friends first then fills excluding self capped at five`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Alpha")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1"))
        fake._following.value = setOf("f1", "f2")
        // self + 2 friends + 4 others, interleaved, to prove ordering and the self/cap rules.
        fake._groupMembers.value = mapOf("g1" to listOf("me", "x1", "f1", "x2", "x3", "f2", "x4"))

        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        // Friends first (member order), then other members to fill, self excluded, max 5.
        assertEquals(
            listOf("f1", "f2", "x1", "x2", "x3"),
            vm.myGroups.value.single().people.map { it.pubkey },
        )
        // The "N people" count is the full membership (self included).
        assertEquals(7, vm.myGroups.value.single().memberCount)
    }

    @Test
    fun `friendsGroups people shows friends first then fills with other members`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf("alice" to listOf(org.nostr.nostrord.network.UserGroupRef("wss://a", "g1")))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "One")))
        // Member list: the friend plus three non-friends to fill the row.
        fake._groupMembers.value = mapOf("g1" to listOf("alice", "o1", "o2", "o3"))

        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("alice", "o1", "o2", "o3"),
            vm.friendsGroups.value.single().people.map { it.pubkey },
        )
    }

    @Test
    fun `recommendedGroups reads the curator's list excluding mine`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._userGroupLists.value =
            mapOf(
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g1"),
                        org.nostr.nostrord.network.UserGroupRef("wss://a", "g2"),
                    ),
            )
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g2"))
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Curated One")))

        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("g1"), vm.recommendedGroups.value.map { it.meta.id })
        assertEquals("Curated One", vm.recommendedGroups.value.single().meta.name)
    }

    @Test
    fun `friendsGroups and recommendedGroups hide groups on unreachable relays`() = runTest {
        val curator = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"
        val fake = FakeNostrRepository()
        fake._following.value = setOf("alice")
        fake._userGroupLists.value =
            mapOf(
                "alice" to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://up", "gf"),
                        org.nostr.nostrord.network.UserGroupRef("wss://down", "gd"),
                    ),
                curator to
                    listOf(
                        org.nostr.nostrord.network.UserGroupRef("wss://up", "gr"),
                        org.nostr.nostrord.network.UserGroupRef("wss://down", "grd"),
                    ),
            )
        fake._groupsByRelay.value =
            mapOf(
                "wss://up" to listOf(meta("gf", "Friend Up"), meta("gr", "Rec Up")),
                "wss://down" to listOf(meta("gd", "Friend Down"), meta("grd", "Rec Down")),
            )
        // wss://down failed NIP-11 / socket: its groups must not appear on discovery.
        fake._unreachableRelays.value = setOf("wss://down")

        val vm = HomePageViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("gf"), vm.friendsGroups.value.map { it.meta.id })
        assertEquals(listOf("gr"), vm.recommendedGroups.value.map { it.meta.id })
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
