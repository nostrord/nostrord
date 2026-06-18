package org.nostr.nostrord.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.nostrord_logo
import org.jetbrains.compose.resources.painterResource
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.components.forms.FormError
import org.nostr.nostrord.ui.components.forms.FormHint
import org.nostr.nostrord.ui.components.home.GroupCard
import org.nostr.nostrord.ui.components.home.GroupCardSkeleton
import org.nostr.nostrord.ui.components.onboarding.FollowAllButton
import org.nostr.nostrord.ui.components.onboarding.FollowSuggestionRow
import org.nostr.nostrord.ui.components.onboarding.HintRow
import org.nostr.nostrord.ui.components.onboarding.StepLabel
import org.nostr.nostrord.ui.components.onboarding.StepProgress
import org.nostr.nostrord.ui.screens.home.DiscoverGroup
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

private val STEPS = listOf("Welcome", "Who to follow", "Groups")
private val CONTENT_MAX_WIDTH = 672.dp

/**
 * Full-page onboarding wizard (prototype Onboarding, without the follow-pack /
 * avatar collection steps): progress bars + step label, Welcome and Groups steps,
 * and the footer with Back / Skip and the primary action. Shown when the logged-in
 * account's kind:10009 lists no groups; joining a group through [onJoin] updates
 * the list and the gate routes to Home by itself. [onSkip] is the session override.
 */
@Composable
fun OnboardingFlowScreen(
    onSkip: () -> Unit,
    onJoin: (String, (Result<Unit>) -> Unit) -> Unit,
    onJoinGroup: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Re-opened from the sidebar's "Follow people" action: jump straight to the
    // Who-to-follow step rather than the Welcome step.
    var step by remember { mutableStateOf(if (AppModule.onboardingRequested.value) 1 else 0) }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors =
                    listOf(
                        NostrordColors.PageGradientFrom,
                        NostrordColors.Background,
                        NostrordColors.PageGradientTo,
                    ),
                ),
            ),
    ) {
        Column(
            modifier =
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            StepProgress(current = step, total = STEPS.size)
            Spacer(modifier = Modifier.height(8.dp))
            StepLabel(current = step, total = STEPS.size, name = STEPS[step])
        }

        Column(
            modifier =
            Modifier
                .weight(1f)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            when (step) {
                0 -> WelcomeStep()
                1 -> WhoToFollowStep()
                else -> GroupsStep(onJoin, onJoinGroup)
            }
        }

        HorizontalDivider(color = NostrordColors.Divider)
        Row(
            modifier =
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                TextButton(onClick = { step-- }) {
                    Text("Back", color = NostrordColors.TextSecondary)
                }
            } else {
                TextButton(onClick = onSkip) {
                    Text("Skip for now", color = NostrordColors.TextMuted)
                }
            }
            AppButton(
                text = if (step < STEPS.size - 1) "Continue" else "Done",
                onClick = { if (step < STEPS.size - 1) step++ else onSkip() },
                size = AppButtonSize.Large,
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Image(
            painter = painterResource(Res.drawable.nostrord_logo),
            contentDescription = "Nostrord",
            modifier =
            Modifier
                .size(64.dp)
                .clip(NostrordShapes.shapeXLarge)
                .background(NostrordColors.Primary),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Welcome to Nostrord",
            color = NostrordColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Your account is ready and everything technical is handled in the background. " +
                "We'll connect you to people, and they lead you to the right groups.",
            color = NostrordColors.TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 448.dp),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Column(
            modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HintRow(
                icon = Icons.Default.People,
                title = "Follow some people",
                description = "Ready-made packs to start you off with a network",
            )
            HintRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "See their groups",
                description = "We suggest the groups where the people you follow are",
            )
            HintRow(
                icon = Icons.Default.Link,
                title = "Or join by invite",
                description = "Paste a group link to jump straight in",
            )
        }
    }
}

@Composable
private fun WhoToFollowStep() {
    val following by AppModule.nostrRepository.following.collectAsState()
    val metadata by AppModule.nostrRepository.userMetadata.collectAsState()

    LaunchedEffect(Unit) {
        // Pull the existing kind:3 so already-followed people show as "Following", and so the
        // first follow tap skips the contact-list publish's initial fetch wait.
        AppModule.nostrRepository.requestContactList()
        val pubkeys = onboardingFollowSuggestions.map { it.pubkey }.filter { it.isNotBlank() }.toSet()
        if (pubkeys.isNotEmpty()) AppModule.nostrRepository.requestUserMetadata(pubkeys)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Who to follow",
            color = NostrordColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Pick a few people to follow: that's what unlocks group discovery.",
            color = NostrordColors.TextSecondary,
            fontSize = 15.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FollowAllButton(
            people = onboardingFollowSuggestions,
            following = following,
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onboardingFollowSuggestions.forEach { person ->
                FollowSuggestionRow(
                    person = person,
                    pictureUrl = metadata[person.pubkey]?.picture,
                    isFollowing = person.pubkey in following,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Follow at least one person to see group suggestions in the next step.",
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GroupsStep(
    onJoin: (String, (Result<Unit>) -> Unit) -> Unit,
    onJoinGroup: (String, String) -> Unit,
) {
    // Reuse the Home discovery: groups the people you follow are in, via their kind:10009
    // lists and member lists. The relay link is disabled here (no relay route in onboarding).
    val vm = viewModel { HomePageViewModel(AppModule.nostrRepository) }
    val friendsGroups by vm.friendsGroups.collectAsState()
    val friendsGroupsLoading by vm.friendsGroupsLoading.collectAsState()
    val joinedByRelay by AppModule.nostrRepository.joinedGroupsByRelay.collectAsState()
    val joinedGroupIds = joinedByRelay.values.flatten().toSet()

    // Accumulate discovered public groups so a joined one stays on the list (marked
    // "Joined") instead of vanishing: joining removes it from [friendsGroups] (which only
    // lists groups you are NOT in), so keep a sticky union that we only add to / update.
    val shown = remember { mutableStateListOf<DiscoverGroup>() }
    LaunchedEffect(friendsGroups) {
        friendsGroups.filter { it.meta.isPublic }.forEach { fg ->
            // Match by group id (stable identity): the same group can re-arrive with a
            // different relayUrl representation, which would otherwise add a duplicate card.
            val idx = shown.indexOfFirst { it.meta.id == fg.meta.id }
            if (idx >= 0) shown[idx] = fg else shown.add(fg)
        }
    }

    var input by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun joinRef(ref: String) {
        if (ref.isBlank() || isJoining) return
        isJoining = true
        errorMessage = null
        onJoin(ref) { result ->
            isJoining = false
            // Success flips the onboarding gate upstream and lands on Home.
            errorMessage = result.exceptionOrNull()?.message
        }
    }

    fun join() = joinRef(input.trim())

    LaunchedEffect(Unit) { vm.loadFriendsGroups() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Find your group",
            color = NostrordColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Have an invite? Paste it below. Or join a public group the people you follow are in.",
            color = NostrordColors.TextSecondary,
            fontSize = 15.sp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Join-by-link card pinned above the discovery list so an invite (a deterministic
        // action) is always reachable without scrolling past a long list of suggestions.
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.shapeLarge)
                .background(NostrordColors.Surface)
                .border(1.dp, NostrordColors.Divider, NostrordShapes.shapeLarge)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Join by link, naddr or group ID",
                    color = NostrordColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FormError(errorMessage)
            Row(verticalAlignment = Alignment.Top) {
                AppTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorMessage = null
                    },
                    placeholder = "naddr1... or wss://relay'groupId",
                    enabled = !isJoining,
                    onDone = { join() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppButton(
                    text = if (isJoining) "Joining..." else "Join",
                    onClick = { join() },
                    enabled = input.isNotBlank() && !isJoining,
                    variant = AppButtonVariant.Secondary,
                    size = AppButtonSize.Large,
                    loading = isJoining,
                )
            }
            FormHint("Accepts an invite link, a NIP-19 naddr address, or relay'groupId.")
        }

        // One public group per line; relay row hidden in onboarding. Joining marks the
        // card "Joined" in place rather than removing it (see [shown] accumulation above).
        if (shown.isEmpty() && friendsGroupsLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { GroupCardSkeleton() }
            }
        } else if (shown.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { fg ->
                    val joined = fg.meta.id in joinedGroupIds
                    GroupCard(
                        name = fg.meta.name ?: fg.meta.id,
                        description = fg.meta.about,
                        picture = fg.meta.picture,
                        groupId = fg.meta.id,
                        memberCount = fg.memberCount,
                        restricted = fg.meta.isRestricted,
                        cta = if (joined) "Joined" else "Join",
                        ctaPrimary = !joined,
                        ctaInline = true,
                        people = fg.people,
                        peopleLoading = fg.peopleLoading,
                        isPublic = fg.meta.isPublic,
                        isOpen = fg.meta.isOpen,
                        hasMetadata = fg.hasMetadata,
                        relayUrl = "",
                        isJoined = joined,
                        onRelayClick = null,
                        // Fire-and-forget like the follow buttons: the join outlives this
                        // screen, keeps the wizard up, and the card flips to "Joined".
                        onClick = { if (!joined) onJoinGroup(fg.relayUrl, fg.meta.id) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No invite yet? Skip for now and create your own group from Home.",
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
