package org.nostr.nostrord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.parseGroupJoinInput
import org.nostr.nostrord.utils.toKotlinResult

// Grace before deciding the active account "needs onboarding": the kind:10009 list resolves the
// moment the first group arrives, but a genuinely group-less account only resolves after this, so
// a switch never flashes the wizard before the new account's list has loaded.
private const val GROUP_RESOLVE_GRACE_MS = 3_000L

class AppViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val isInitialized = repo.isInitialized
    val isLoggedIn = repo.isLoggedIn
    val isBunkerVerifying = repo.isBunkerVerifying

    // Group-list resolved gate: true once the active account's kind:10009 has actually loaded
    // (the first joined group arrived) or [GROUP_RESOLVE_GRACE_MS] elapsed. Reset + re-armed on
    // every account switch (the activePubkey collector in init), so the onboarding decision is
    // never made while the new account's list is still empty mid-reload.
    private val _groupsResolved = MutableStateFlow(false)

    private fun armGroupsResolve() {
        viewModelScope.launch {
            withTimeoutOrNull(GROUP_RESOLVE_GRACE_MS) {
                repo.joinedGroupsByRelay.first { byRelay -> byRelay.values.any { it.isNotEmpty() } }
            }
            _groupsResolved.value = true
        }
    }

    /**
     * Onboarding gate: true once we've resolved that the active account's kind:10009 lists no
     * groups at all on any relay (and relay discovery is not still running). Accounts with groups
     * land straight on Home; while the list is still resolving, [onboardingDecisionPending] holds
     * the loading screen instead of guessing.
     */
    val needsOnboarding: StateFlow<Boolean> =
        combine(repo.joinedGroupsByRelay, repo.isDiscoveringRelays, _groupsResolved) { joined, discovering, resolved ->
            resolved && !discovering && joined.values.all { it.isEmpty() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True during the post-login window where we don't yet know whether the active account has
     * groups: its list hasn't resolved and currently looks empty. Both UIs show the loading screen
     * here (rather than the home skeleton), then route to Home or onboarding once it resolves.
     */
    val onboardingDecisionPending: StateFlow<Boolean> =
        combine(repo.joinedGroupsByRelay, _groupsResolved) { joined, resolved ->
            !resolved && joined.values.all { it.isEmpty() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    // Re-open request from the friends sidebar's "Follow people" action. Folded into the
    // onboarding gate by both UIs so it re-opens the wizard even for an account that has
    // groups (needsOnboarding == false) or already skipped (onboardingSkipped == true).
    val onboardingRequested: StateFlow<Boolean> = AppModule.onboardingRequested

    fun keepOnboarding() {
        _stayInOnboarding.value = true
    }

    fun skipOnboarding() {
        _stayInOnboarding.value = false
        _onboardingSkipped.value = true
        AppModule.clearOnboardingRequest()
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
        armGroupsResolve()
        // The VM outlives account switches: re-arm the onboarding decision per account so a switch
        // never flashes the wizard (or carries a prior account's Skip) before the new account's
        // group list loads. drop(1): the current account is covered by the arming above.
        viewModelScope.launch {
            repo.activePubkey.drop(1).collect {
                _groupsResolved.value = false
                _onboardingSkipped.value = false
                _stayInOnboarding.value = false
                AppModule.clearOnboardingRequest()
                armGroupsResolve()
            }
        }
        viewModelScope.launch {
            withTimeoutOrNull(30_000) {
                repo.initialize()
            } ?: repo.forceInitialized()
        }
    }
}
