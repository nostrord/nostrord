package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(fake: FakeNostrRepository = FakeNostrRepository()) =
        GroupViewModel(fake, "test-group")

    // -------------------------------------------------------------------------
    // State passthrough
    // -------------------------------------------------------------------------

    @Test
    fun `joinedGroups reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        fake._joinedGroups.value = setOf("test-group")
        val vm = vm(fake)

        assertTrue(vm.joinedGroups.value.contains("test-group"))
    }

    @Test
    fun `isLoadingMore starts false for group`() = runTest {
        val vm = vm()
        assertFalse(vm.isLoadingMore.value["test-group"] ?: false)
    }

    // -------------------------------------------------------------------------
    // leaveGroup — onSuccess callback
    // -------------------------------------------------------------------------

    @Test
    fun `leaveGroup calls onSuccess after repo succeeds`() = runTest {
        val vm = vm()
        var called = false

        vm.leaveGroup { called = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(called)
    }

    @Test
    fun `leaveGroup passes correct groupId to repo`() = runTest {
        val fake = FakeNostrRepository()
        var calledWith: String? = null
        fake.leaveGroupAction = { id, _ -> calledWith = id; org.nostr.nostrord.utils.Result.Success(Unit) }
        val vm = GroupViewModel(fake, "my-group")

        vm.leaveGroup {}
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("my-group", calledWith)
    }

    // -------------------------------------------------------------------------
    // deleteGroup — onSuccess callback
    // -------------------------------------------------------------------------

    @Test
    fun `deleteGroup calls onSuccess after repo succeeds`() = runTest {
        val vm = vm()
        var called = false

        vm.deleteGroup { called = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(called)
    }

    // -------------------------------------------------------------------------
    // sendMessage — delegates with correct groupId
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage delegates with correct groupId`() = runTest {
        val fake = FakeNostrRepository()
        var sentGroupId: String? = null
        var sentContent: String? = null
        fake.sendMessageAction = { gid, content, _, _, _ ->
            sentGroupId = gid
            sentContent = content
            org.nostr.nostrord.utils.Result.Success(Unit)
        }
        val vm = GroupViewModel(fake, "chat-room")

        vm.sendMessage("hello", null, emptyMap(), null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("chat-room", sentGroupId)
        assertEquals("hello", sentContent)
    }
}
