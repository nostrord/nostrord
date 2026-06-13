package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePageViewModelTest {
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
    ) = GroupMetadata(id = id, name = name, about = null, picture = null, isPublic = true, isOpen = true)

    @Test
    fun `groupsWithUser lists joined groups containing the pubkey with role`() = runTest {
        val fake = FakeNostrRepository()
        fake._groupsByRelay.value = mapOf("wss://a" to listOf(meta("g1", "Alpha"), meta("g2", "Beta")))
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1", "g2"))
        fake._groupMembers.value = mapOf("g1" to listOf("pk1", "pk2"), "g2" to listOf("pk2"))
        fake._groupAdmins.value = mapOf("g1" to listOf("pk1"))

        val vm = ProfilePageViewModel(fake, "pk1")
        testDispatcher.scheduler.advanceUntilIdle()

        val groups = vm.groupsWithUser.value
        assertEquals(listOf("g1"), groups.map { it.meta.id })
        assertTrue(groups.single().isAdmin)
        assertEquals(2, groups.single().memberCount)
        assertTrue(vm.isAdminSomewhere.value)

        val vm2 = ProfilePageViewModel(fake, "pk2")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("g1", "g2"), vm2.groupsWithUser.value.map { it.meta.id })
        assertEquals(false, vm2.isAdminSomewhere.value)
    }

    @Test
    fun `toggleFollow follows then unfollows the user`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "me"
        val vm = ProfilePageViewModel(fake, "pk1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isFollowing.value)

        vm.toggleFollow()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isFollowing.value)
        assertTrue("pk1" in fake.following.value)

        vm.toggleFollow()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.isFollowing.value)
        assertEquals(false, "pk1" in fake.following.value)
    }
}
