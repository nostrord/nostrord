package org.nostr.nostrord.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.group.GroupMembership
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.deriveMembershipStatus
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // Drain Main-dispatched viewModelScope jobs before resetMain so an un-run task
        // doesn't race the dispatcher swap and surface as UncaughtExceptionsBeforeTest
        // in a later test (see AppViewModelTest).
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun vm(fake: FakeNostrRepository = FakeNostrRepository()) = GroupViewModel(fake, "test-group")

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
    // Moderation actions (#174): errors must surface, never fail silently
    // -------------------------------------------------------------------------

    @Test
    fun `promoteToAdmin sends the admin role and reports success`() = runTest {
        val fake = FakeNostrRepository()
        val vm = vm(fake)

        var succeeded = false
        vm.promoteToAdmin("target") { succeeded = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(Triple("test-group", "target", listOf("admin")), fake.addUserCalls.single())
        assertTrue(succeeded)
        assertNull(vm.moderationError.value)
    }

    @Test
    fun `promote failure surfaces the relay reason without the blocked prefix`() = runTest {
        val fake = FakeNostrRepository()
        fake.addUserResult =
            Result.Error(AppError.Group.SendFailed("test-group", Exception("blocked: not an admin")))
        val vm = vm(fake)

        var succeeded = false
        vm.promoteToAdmin("target") { succeeded = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(succeeded)
        assertEquals("Not an admin", vm.moderationError.value)
    }

    @Test
    fun `remove timeout surfaces the friendly AppError message`() = runTest {
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Timed out waiting for relay confirmation (group: test-group)",
            vm.moderationError.value,
        )
    }

    @Test
    fun `moderation is busy while awaiting the relay and idle after`() = runTest {
        val fake = FakeNostrRepository()
        val gate = CompletableDeferred<Unit>()
        fake.addUserGate = gate
        val vm = vm(fake)

        vm.promoteToAdmin("target")
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.moderationBusy.value)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.moderationBusy.value)
    }

    @Test
    fun `a member list change clears a stale moderation error`() = runTest {
        // Offline remove: the await times out (error shown), but the queued event is
        // flushed on reconnect and the relay echo updates the member list. The stale
        // error must not outlive the visible success.
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "Timed out waiting for relay confirmation (group: test-group)",
            vm.moderationError.value,
        )

        fake._groupMembers.value = mapOf("test-group" to listOf("someone-else"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.moderationError.value)
    }

    @Test
    fun `a successful action clears the previous moderation error`() = runTest {
        val fake = FakeNostrRepository()
        fake.removeUserResult = Result.Error(AppError.Group.SendTimeout("test-group"))
        val vm = vm(fake)

        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()
        fake.removeUserResult = Result.Success(Unit)
        vm.removeUser("target")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.moderationError.value)
    }

    @Test
    fun `reactions from muted users are hidden and empty entries dropped`() = runTest {
        val fake = FakeNostrRepository()
        fake._reactions.value = mapOf(
            "m1" to mapOf(
                "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("friend", "spammer")),
                "🔥" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("spammer")),
            ),
            "m2" to mapOf(
                "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("spammer")),
            ),
        )
        fake._mutedPubkeys.value = setOf("spammer")
        val vm = vm(fake)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            mapOf(
                "m1" to mapOf(
                    "👍" to org.nostr.nostrord.network.managers.GroupManager.ReactionInfo(null, listOf("friend")),
                ),
            ),
            vm.reactions.value,
        )
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
        fake.leaveGroupAction = { id, _ ->
            calledWith = id
            org.nostr.nostrord.utils.Result
                .Success(Unit)
        }
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
            org.nostr.nostrord.utils.Result
                .Success(Unit)
        }
        val vm = GroupViewModel(fake, "chat-room")

        vm.sendMessage("hello", null, emptyMap(), null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("chat-room", sentGroupId)
        assertEquals("hello", sentContent)
    }

    // -------------------------------------------------------------------------
    // Optimistic send: retry / dismiss delegate with the right ids
    // -------------------------------------------------------------------------

    @Test
    fun `retrySend delegates the event id`() = runTest {
        val fake = FakeNostrRepository()
        var retried: String? = null
        fake.retrySendAction = { retried = it }
        val vm = GroupViewModel(fake, "chat-room")

        vm.retrySend("evt-1")

        assertEquals("evt-1", retried)
    }

    @Test
    fun `dismissFailed delegates the screen groupId and event id`() = runTest {
        val fake = FakeNostrRepository()
        var dGroup: String? = null
        var dEvent: String? = null
        fake.dismissFailedAction = { gid, eid ->
            dGroup = gid
            dEvent = eid
        }
        val vm = GroupViewModel(fake, "chat-room")

        vm.dismissFailed("evt-2")

        assertEquals("chat-room", dGroup)
        assertEquals("evt-2", dEvent)
    }

    // -------------------------------------------------------------------------
    // Membership derivation (shared by Compose + web; drives join/pending/leave UX)
    // -------------------------------------------------------------------------

    private val me = "me-pubkey"

    private fun status(
        joined: Boolean = false,
        isOpen: Boolean = true,
        hasOwnJoinRequest: Boolean = false,
        members: List<String> = emptyList(),
        admins: List<String> = emptyList(),
        pubkey: String? = me,
        locallyLeft: Boolean = false,
    ) = deriveMembershipStatus(pubkey, joined, isOpen, hasOwnJoinRequest, members, admins, locallyLeft)

    @Test
    fun `locally left beats a stale relay member list`() {
        // 0xchat keeps us in 39002 after our 9022; the durable leave marker must still read NONE.
        assertEquals(GroupMembership.NONE, status(members = listOf(me), locallyLeft = true))
    }

    @Test
    fun `locally left beats a stale admin list`() {
        assertEquals(GroupMembership.NONE, status(admins = listOf(me), locallyLeft = true))
    }

    @Test
    fun `locally left with an outstanding feed request is still NONE`() {
        // Cancel (9022) leaves a stale 9021 in the feed; locallyLeft wins.
        assertEquals(
            GroupMembership.NONE,
            status(isOpen = false, hasOwnJoinRequest = true, locallyLeft = true),
        )
    }

    @Test
    fun `not left and in members is still MEMBER`() {
        // No regression: the durable override only triggers when actually left.
        assertEquals(GroupMembership.MEMBER, status(members = listOf(me), locallyLeft = false))
    }

    @Test
    fun `no pubkey is NONE`() {
        assertEquals(GroupMembership.NONE, status(joined = true, members = listOf(me), pubkey = null))
    }

    @Test
    fun `admin wins even before the member list loads`() {
        assertEquals(GroupMembership.ADMIN, status(joined = true, admins = listOf(me)))
    }

    @Test
    fun `not joined with no outstanding request is NONE`() {
        assertEquals(GroupMembership.NONE, status(joined = false, members = listOf("someone")))
    }

    @Test
    fun `in the member list is MEMBER`() {
        assertEquals(GroupMembership.MEMBER, status(joined = true, members = listOf(me, "other")))
    }

    @Test
    fun `joined but absent from a loaded member list is PENDING`() {
        assertEquals(GroupMembership.PENDING, status(joined = true, members = listOf("other")))
    }

    @Test
    fun `closed group with an outstanding request reads PENDING before the member list loads`() {
        assertEquals(GroupMembership.PENDING, status(joined = true, isOpen = false, hasOwnJoinRequest = true))
    }

    @Test
    fun `open group just joined is RESOLVING not an admin-approval flash`() {
        // Open groups auto-approve, so "pending admin approval" would be wrong copy; wait for the list.
        assertEquals(GroupMembership.RESOLVING, status(joined = true, isOpen = true, hasOwnJoinRequest = true))
    }
}
