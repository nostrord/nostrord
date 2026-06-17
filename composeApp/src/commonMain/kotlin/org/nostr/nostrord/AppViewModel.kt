package org.nostr.nostrord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.parseGroupJoinInput
import org.nostr.nostrord.utils.toKotlinResult

class AppViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val isInitialized = repo.isInitialized
    val isLoggedIn = repo.isLoggedIn
    val isBunkerVerifying = repo.isBunkerVerifying

    /**
     * Onboarding gate: true while the active account's kind:10009 lists no groups at
     * all on any relay (and relay discovery is not still running, so a returning
     * account doesn't flash the onboarding before its list arrives). Accounts with
     * groups land straight on Home.
     */
    val needsOnboarding: StateFlow<Boolean> =
        combine(repo.joinedGroupsByRelay, repo.isDiscoveringRelays) { joined, discovering ->
            !discovering && joined.values.all { it.isEmpty() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Session-level "Skip for now": the gate is state-derived, so skipping needs an
    // explicit override or the user could never reach Home without joining a group.
    private val _onboardingSkipped = MutableStateFlow(false)
    val onboardingSkipped: StateFlow<Boolean> = _onboardingSkipped.asStateFlow()

    // Latch that keeps the wizard up after the user joins a group from its "Find your
    // group" step: joining flips [needsOnboarding] false, which would otherwise yank
    // them straight to Home and prevent joining several. Set only on an explicit join
    // (never on a cold-load blip), and cleared when they leave via [skipOnboarding].
    private val _stayInOnboarding = MutableStateFlow(false)
    val stayInOnboarding: StateFlow<Boolean> = _stayInOnboarding.asStateFlow()

    fun keepOnboarding() {
        _stayInOnboarding.value = true
    }

    fun skipOnboarding() {
        _stayInOnboarding.value = false
        _onboardingSkipped.value = true
    }

    /**
     * Join a group from the onboarding's invite/naddr/address input: parse, switch to
     * the hosting relay and send the join. On success the kind:10009 update flips
     * [needsOnboarding] and the app lands on Home by itself.
     */
    fun joinGroupFromInput(
        input: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val target = parseGroupJoinInput(input)
        if (target == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid invite link, naddr or group address")))
            return
        }
        // Optimistic, like the follow button: flip the group to joined right away so the
        // card reads "Joined" without waiting on the relay switch + signer + send. The
        // confirmed join persists it; a failure rolls the optimistic flip back.
        val optimistic = repo.markOptimisticJoin(target.relayUrl, target.groupId)
        viewModelScope.launch {
            try {
                if (repo.currentRelayUrl.value != target.relayUrl) {
                    repo.switchRelay(target.relayUrl)
                }
                val result = repo.joinGroup(target.groupId, target.inviteCode).toKotlinResult()
                if (result.isFailure && optimistic) repo.revertOptimisticJoin(target.relayUrl, target.groupId)
                onResult(result)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (optimistic) repo.revertOptimisticJoin(target.relayUrl, target.groupId)
                onResult(Result.failure(e))
            }
        }
    }

    init {
        viewModelScope.launch {
            withTimeoutOrNull(30_000) {
                repo.initialize()
            } ?: repo.forceInitialized()
        }
    }
}
