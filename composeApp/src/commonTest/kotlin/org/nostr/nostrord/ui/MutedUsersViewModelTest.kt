package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.settings.MutedUsersViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MutedUsersViewModelTest {
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

    @Test
    fun `muted list mirrors the repo set sorted`() = runTest {
        val repo = FakeNostrRepository()
        repo._mutedPubkeys.value = setOf("ccc", "aaa", "bbb")
        val vm = MutedUsersViewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("aaa", "bbb", "ccc"), vm.muted.value)
    }

    @Test
    fun `unmute delegates to the repo and drops the entry`() = runTest {
        val repo = FakeNostrRepository()
        repo._mutedPubkeys.value = setOf("aaa", "bbb")
        val vm = MutedUsersViewModel(repo)

        vm.unmute("aaa")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("unmuteUser:aaa" in repo.calls)
        assertEquals(listOf("bbb"), vm.muted.value)
    }
}
