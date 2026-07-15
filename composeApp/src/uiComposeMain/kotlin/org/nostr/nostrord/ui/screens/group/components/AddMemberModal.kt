package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.screens.group.FriendCandidate
import org.nostr.nostrord.ui.screens.group.filterFriendCandidates
import org.nostr.nostrord.ui.screens.group.pubkeyUsesRelay
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Modal for adding a user to the group: pick a follow from the searchable list, or
 * paste an npub / hex pubkey. The input doubles as the friend-search field.
 *
 * The "Send a DM invite" checkbox defaults per target: someone whose public kind:10009
 * pins a group on this relay already gets the in-app add notification there, so the DM
 * defaults off for them and on for everyone else. Toggling it is an explicit choice
 * that applies to whoever is added next.
 */
@Composable
fun AddMemberModal(
    onAddMember: (pubkey: String, notifyViaDm: Boolean) -> Unit,
    onDismiss: () -> Unit,
    friends: List<FriendCandidate> = emptyList(),
    groupRelay: String? = null,
    userGroupLists: Map<String, List<UserGroupRef>> = emptyMap(),
    onPrefetchTarget: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    // Submit only unlocks for a key that actually parses (npub/nprofile/hex).
    val parsedHex = (Nip19.parsePubkeyInput(input) as? Nip19.PubkeyParse.Ok)?.hex
    val isValidKey = parsedHex != null

    var notifyViaDm by remember { mutableStateOf(true) }
    var notifyTouched by remember { mutableStateOf(false) }

    fun targetOnRelay(pubkey: String) = pubkeyUsesRelay(pubkey, groupRelay, userGroupLists)
    val typedOnRelay = parsedHex != null && targetOnRelay(parsedHex)

    // Fetch the typed key's kind:10009 so the on-relay check has data (friends' lists
    // are already fetched by the home discovery).
    LaunchedEffect(parsedHex) { parsedHex?.let(onPrefetchTarget) }
    // Smart default; an explicit toggle wins from then on.
    LaunchedEffect(parsedHex, typedOnRelay) {
        if (!notifyTouched && parsedHex != null) notifyViaDm = !typedOnRelay
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun submit() {
        // Validation lives in commonMain (Nip19.parsePubkeyInput) so this and the
        // web modal stay in sync — they both accept npub / nprofile / hex (any
        // case) and reject nsec with a specific warning.
        when (val parsed = Nip19.parsePubkeyInput(input)) {
            is Nip19.PubkeyParse.Ok -> {
                onAddMember(parsed.hex, notifyViaDm)
                onDismiss()
            }
            Nip19.PubkeyParse.Empty -> error = "Enter an npub or hex pubkey."
            Nip19.PubkeyParse.IsPrivateKey ->
                error = "That looks like a private key (nsec). Use the user's npub instead."
            Nip19.PubkeyParse.NotAPubkey ->
                error = "That's not a user identity. Paste an npub, nprofile, or hex pubkey."
            Nip19.PubkeyParse.Malformed -> error = "Invalid npub or hex pubkey."
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(modifier = Modifier.padding(Spacing.xxl)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = NostrordColors.Primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Add Member",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Description
                    Text(
                        text = "Pick a friend below, or enter the user's npub or hex public key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NostrordColors.TextSecondary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Input field
                    AppField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        placeholder = "Search friends, or npub1... / hex pubkey",
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                    submit()
                                    true
                                } else if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                    onDismiss()
                                    true
                                } else {
                                    false
                                }
                            },
                    )

                    // Error message
                    if (error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error!!,
                            color = NostrordColors.Error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    // Friend picker: follows filtered by the same input (until it turns
                    // into a parseable key, at which point the button flow takes over).
                    val shownFriends = if (isValidKey) emptyList() else filterFriendCandidates(friends, input)
                    if (shownFriends.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "FRIENDS",
                            color = NostrordColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(shownFriends, key = { it.pubkey }) { friend ->
                                Row(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            // Untouched checkbox = per-target auto (the row's
                                            // "on this relay" tag shows why no DM goes out).
                                            val notify = if (notifyTouched) notifyViaDm else !targetOnRelay(friend.pubkey)
                                            onAddMember(friend.pubkey, notify)
                                            onDismiss()
                                        }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OptimizedSmallAvatar(
                                        imageUrl = friend.picture,
                                        identifier = friend.pubkey,
                                        displayName = friend.name ?: friend.pubkey,
                                        size = 32.dp,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = friend.name ?: (friend.pubkey.take(8) + "…"),
                                        color = NostrordColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (targetOnRelay(friend.pubkey)) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "on this relay",
                                            color = NostrordColors.TextMuted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // DM courtesy toggle. The DM is the only signal that reaches a user
                    // whose client does not connect to this relay.
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                notifyViaDm = !notifyViaDm
                                notifyTouched = true
                            }
                            .pointerHoverIcon(PointerIcon.Hand),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = notifyViaDm,
                            onCheckedChange = {
                                notifyViaDm = it
                                notifyTouched = true
                            },
                            colors =
                            CheckboxDefaults.colors(
                                checkedColor = NostrordColors.Primary,
                                uncheckedColor = NostrordColors.TextMuted,
                            ),
                        )
                        Column {
                            Text(
                                text = "Send a DM invite",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NostrordColors.TextPrimary,
                            )
                            Text(
                                text =
                                if (typedOnRelay) {
                                    "This user is already on this relay and gets the in-app notification."
                                } else {
                                    "A DM is how someone outside this relay finds out."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = NostrordColors.TextMuted,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = "Cancel",
                                color = NostrordColors.TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { submit() },
                            enabled = isValidKey,
                            colors =
                            ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.Primary,
                                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.3f),
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text("Add Member")
                        }
                    }
                }
            }
        }
    }
}
