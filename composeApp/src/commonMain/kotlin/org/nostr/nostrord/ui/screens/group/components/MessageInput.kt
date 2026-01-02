package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Message input field with standard keyboard behavior.
 *
 * Keyboard behavior:
 * - Enter: Send message
 * - Shift+Enter: Insert newline
 * - Escape: Close mention popup
 *
 * Features:
 * - @mention autocomplete popup
 * - Multi-line text input (up to 4 lines visible)
 * - Join prompt when not a group member
 * - Loading spinner when sending message
 */
@Composable
fun MessageInput(
    isJoined: Boolean,
    selectedChannel: String,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit,
    groupMembers: List<MemberInfo> = emptyList(),
    mentions: Map<String, String> = emptyMap(), // displayName -> pubkey
    onMentionsChange: (Map<String, String>) -> Unit = {},
    isSending: Boolean = false
) {
    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionQuery by remember { mutableStateOf("") }

    // Track text changes to detect "@" trigger
    fun handleTextChange(newText: String) {
        val oldText = messageInput

        // Detect if "@" was just typed
        if (newText.length > oldText.length) {
            val addedChar = newText.getOrNull(newText.length - 1)
            if (addedChar == '@') {
                // Check if it's at the start or after a space
                val prevChar = newText.getOrNull(newText.length - 2)
                if (prevChar == null || prevChar.isWhitespace()) {
                    showMentionPopup = true
                    mentionStartIndex = newText.length - 1
                    mentionQuery = ""
                }
            } else if (showMentionPopup && mentionStartIndex >= 0) {
                // Update the query as user types after "@"
                val queryPart = newText.substring(mentionStartIndex + 1)
                // Stop if space is typed
                if (queryPart.contains(' ')) {
                    showMentionPopup = false
                    mentionStartIndex = -1
                    mentionQuery = ""
                } else {
                    mentionQuery = queryPart
                }
            }
        } else if (newText.length < oldText.length && showMentionPopup) {
            // Text was deleted
            if (mentionStartIndex >= newText.length) {
                // "@" was deleted
                showMentionPopup = false
                mentionStartIndex = -1
                mentionQuery = ""
            } else {
                // Update query
                mentionQuery = newText.substring(mentionStartIndex + 1)
            }
        }

        onMessageInputChange(newText)
    }

    fun handleMemberSelect(member: MemberInfo) {
        // Replace "@query" with "@displayName "
        val beforeMention = messageInput.substring(0, mentionStartIndex)
        val afterMention = if (mentionStartIndex + 1 + mentionQuery.length < messageInput.length) {
            messageInput.substring(mentionStartIndex + 1 + mentionQuery.length)
        } else {
            ""
        }
        val newText = "$beforeMention@${member.displayName} $afterMention"

        onMessageInputChange(newText)

        // Add displayName -> pubkey mapping
        if (!mentions.containsKey(member.displayName)) {
            onMentionsChange(mentions + (member.displayName to member.pubkey))
        }

        showMentionPopup = false
        mentionStartIndex = -1
        mentionQuery = ""
    }

    if (!isJoined) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.SurfaceVariant)
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Join the group to send messages",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.MessageBody
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                TextButton(
                    onClick = onJoinGroup,
                    colors = ButtonDefaults.textButtonColors(contentColor = NostrordColors.Primary)
                ) {
                    Text("Join Now", style = NostrordTypography.Button)
                }
            }
        }
    } else {
        val density = LocalDensity.current
        val textFieldInteractionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.SurfaceVariant)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = messageInput,
                    onValueChange = { handleTextChange(it) },
                    interactionSource = textFieldInteractionSource,
                    placeholder = {
                        Text(
                            "Message #$selectedChannel",
                            style = NostrordTypography.InputPlaceholder,
                            color = NostrordColors.TextMuted
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clip(NostrordShapes.inputShape)
                        .onPreviewKeyEvent { event ->
                            when {
                                // Escape closes the mention popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Escape &&
                                showMentionPopup -> {
                                    showMentionPopup = false
                                    mentionStartIndex = -1
                                    mentionQuery = ""
                                    true
                                }
                                // Enter (without Shift) sends the message
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed -> {
                                    if (messageInput.isNotBlank()) {
                                        onSendMessage()
                                    }
                                    true // Consume event to prevent newline
                                }
                                // Shift+Enter allows default behavior (newline)
                                else -> false
                            }
                        },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = NostrordColors.InputBackground,
                        unfocusedContainerColor = NostrordColors.InputBackground,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedPlaceholderColor = NostrordColors.TextMuted,
                        unfocusedPlaceholderColor = NostrordColors.TextMuted,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = NostrordTypography.Input,
                    shape = NostrordShapes.inputShape,
                    singleLine = false,
                    maxLines = 4
                )

                // Show loading spinner only when sending (Enter to send, no visible button)
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(start = Spacing.sm)
                            .size(Spacing.iconMd),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp
                    )
                }
            }

            // Floating mention popup above the input
            if (showMentionPopup && groupMembers.isNotEmpty()) {
                val popupOffsetY = with(density) { (-Spacing.sm).roundToPx() }

                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(
                        x = with(density) { Spacing.inputPadding.roundToPx() },
                        y = popupOffsetY
                    ),
                    onDismissRequest = {
                        showMentionPopup = false
                        mentionStartIndex = -1
                        mentionQuery = ""
                    },
                    properties = PopupProperties(focusable = false)
                ) {
                    MentionPopup(
                        members = groupMembers,
                        query = mentionQuery,
                        onMemberSelect = { handleMemberSelect(it) }
                    )
                }
            }
        }
    }
}
