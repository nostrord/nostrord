package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import org.nostr.nostrord.getPlatform
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import androidx.compose.ui.text.font.FontFamily
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily

/**
 * Message input field with Discord-style keyboard behavior.
 *
 * Keyboard behavior:
 * - Enter: Send message (or select mention if popup open)
 * - Shift+Enter: Insert newline at cursor (manually handled for reliability)
 * - Escape: Close mention popup
 * - Tab: Select highlighted mention
 *
 * Features:
 * - Send button (disabled when empty, shows spinner when sending)
 * - @mention autocomplete popup
 * - Multi-line text input (up to 4 lines visible)
 * - Join prompt when not a group member
 */
@Composable
fun MessageInput(
    isJoined: Boolean,
    selectedChannel: String,
    groupName: String?,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit,
    groupMembers: List<MemberInfo> = emptyList(),
    mentions: Map<String, String> = emptyMap(), // displayName -> pubkey
    onMentionsChange: (Map<String, String>) -> Unit = {},
    replyingToMessage: NostrGroupClient.NostrMessage? = null,
    replyingToMetadata: UserMetadata? = null,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onCancelReply: () -> Unit = {},
    isSending: Boolean = false
) {
    var showMentionPopup by remember { mutableStateOf(false) }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionSelectedIndex by remember { mutableStateOf(0) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val showEmojiButton = remember {
        val platform = getPlatform().name
        !platform.startsWith("Android") && !platform.startsWith("iOS")
    }

    // Auto-focus on desktop only — on Android this would open the keyboard immediately
    LaunchedEffect(isJoined, groupName) {
        if (isJoined && !getPlatform().name.startsWith("Android")) {
            focusRequester.requestFocus()
        }
    }

    // Local TextFieldValue state for cursor position control
    var textFieldValue by remember { mutableStateOf(TextFieldValue(messageInput)) }

    // Sync with external messageInput when it changes (e.g., cleared after send)
    LaunchedEffect(messageInput) {
        if (textFieldValue.text != messageInput) {
            textFieldValue = TextFieldValue(messageInput, TextRange(messageInput.length))
        }
    }

    /**
     * Finds the mention context at the given cursor position.
     * Returns the start index of '@' if cursor is in a valid mention context, -1 otherwise.
     * A valid mention context is: cursor is after '@' with no space between '@' and cursor,
     * and '@' is at start of text or preceded by whitespace.
     */
    fun findMentionContext(text: String, cursorPosition: Int): Pair<Int, String> {
        if (cursorPosition <= 0 || cursorPosition > text.length) {
            return Pair(-1, "")
        }

        // Search backwards from cursor to find '@'
        val textBeforeCursor = text.substring(0, cursorPosition)
        val lastAtIndex = textBeforeCursor.lastIndexOf('@')

        if (lastAtIndex == -1) {
            return Pair(-1, "")
        }

        // Check if '@' is at start or after whitespace
        val charBeforeAt = text.getOrNull(lastAtIndex - 1)
        if (charBeforeAt != null && !charBeforeAt.isWhitespace()) {
            return Pair(-1, "")
        }

        // Get the text between '@' and cursor
        val queryPart = text.substring(lastAtIndex + 1, cursorPosition)

        // If there's a space in the query, it's not a valid mention context
        if (queryPart.contains(' ') || queryPart.contains('\n')) {
            return Pair(-1, "")
        }

        return Pair(lastAtIndex, queryPart)
    }

    /**
     * Updates mention popup state based on current cursor position.
     * Called on every text/cursor change to handle typing, backspace, clicks, arrow keys, etc.
     */
    fun updateMentionState(value: TextFieldValue) {
        val cursorPosition = value.selection.start
        val (atIndex, query) = findMentionContext(value.text, cursorPosition)

        if (atIndex >= 0) {
            val queryChanged = mentionQuery != query
            showMentionPopup = true
            showEmojiPicker = false
            mentionStartIndex = atIndex
            mentionQuery = query
            if (queryChanged) {
                mentionSelectedIndex = 0 // Reset selection when query changes
            }
        } else {
            showMentionPopup = false
            mentionStartIndex = -1
            mentionQuery = ""
        }
    }

    // Handle text field value changes (text or cursor position)
    fun handleTextFieldValueChange(newValue: TextFieldValue) {
        textFieldValue = newValue
        onMessageInputChange(newValue.text)
        updateMentionState(newValue)
    }

    fun handleMemberSelect(member: MemberInfo) {
        // Replace "@query" with "@displayName " (exactly one space after)
        val currentText = textFieldValue.text
        val beforeMention = currentText.substring(0, mentionStartIndex)
        val afterMention = if (mentionStartIndex + 1 + mentionQuery.length < currentText.length) {
            currentText.substring(mentionStartIndex + 1 + mentionQuery.length).trimStart()
        } else {
            ""
        }
        val mentionPart = "@${member.displayName} "
        val newText = if (afterMention.isEmpty()) {
            "$beforeMention$mentionPart"
        } else {
            "$beforeMention$mentionPart$afterMention"
        }

        // Calculate cursor position: right after the mention and space
        val cursorPosition = beforeMention.length + mentionPart.length

        // Update with new text and cursor position
        textFieldValue = TextFieldValue(newText, TextRange(cursorPosition))
        onMessageInputChange(newText)

        // Add displayName -> pubkey mapping
        if (!mentions.containsKey(member.displayName)) {
            onMentionsChange(mentions + (member.displayName to member.pubkey))
        }

        showMentionPopup = false
        mentionStartIndex = -1
        mentionQuery = ""

        // Focus the input field after selection
        focusRequester.requestFocus()
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
        val textFieldInteractionSource = remember { MutableInteractionSource() }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Reply preview bar (shown when replying to a message)
            if (replyingToMessage != null) {
                ReplyingToBar(
                    message = replyingToMessage,
                    metadata = replyingToMetadata,
                    userMetadata = userMetadata,
                    onCancelReply = onCancelReply
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Input row
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.SurfaceVariant)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MessageUploadButton(
                    onUrlReady = { url ->
                        val current = textFieldValue.text
                        val separator = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
                        val newText = current + separator + url
                        textFieldValue = TextFieldValue(newText, TextRange(newText.length))
                        onMessageInputChange(newText)
                    }
                )

                TextField(
                    value = textFieldValue,
                    onValueChange = { handleTextFieldValueChange(it) },
                    interactionSource = textFieldInteractionSource,
                    placeholder = {
                        Text(
                            "Message ${groupName ?: selectedChannel}",
                            style = NostrordTypography.InputPlaceholder,
                            color = NostrordColors.TextMuted
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clip(NostrordShapes.inputShape)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                showMentionPopup = false
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            val filteredMembers = getFilteredMembers(groupMembers, mentionQuery)
                            when {
                                // Escape closes the emoji picker first, then mention popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Escape &&
                                showEmojiPicker -> {
                                    showEmojiPicker = false
                                    true
                                }
                                // Escape closes the mention popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Escape &&
                                showMentionPopup -> {
                                    showMentionPopup = false
                                    mentionStartIndex = -1
                                    mentionQuery = ""
                                    true
                                }
                                // Arrow Up - navigate up in mention popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp &&
                                showMentionPopup &&
                                filteredMembers.isNotEmpty() -> {
                                    mentionSelectedIndex = (mentionSelectedIndex - 1).coerceAtLeast(0)
                                    true
                                }
                                // Arrow Down - navigate down in mention popup
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionDown &&
                                showMentionPopup &&
                                filteredMembers.isNotEmpty() -> {
                                    mentionSelectedIndex = (mentionSelectedIndex + 1).coerceAtMost(filteredMembers.size - 1)
                                    true
                                }
                                // Shift+Enter: manually insert newline at cursor (Discord-style)
                                // Returning false here is unreliable in Compose Desktop — insert explicitly.
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                event.isShiftPressed -> {
                                    val sel = textFieldValue.selection
                                    val text = textFieldValue.text
                                    val newText = text.substring(0, sel.start) + "\n" + text.substring(sel.end)
                                    val newValue = TextFieldValue(newText, TextRange(sel.start + 1))
                                    textFieldValue = newValue
                                    onMessageInputChange(newText)
                                    updateMentionState(newValue)
                                    true
                                }
                                // Enter selects mention if popup is open, otherwise sends message
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed -> {
                                    if (showMentionPopup && filteredMembers.isNotEmpty()) {
                                        val selectedMember = filteredMembers.getOrNull(mentionSelectedIndex)
                                        if (selectedMember != null) {
                                            handleMemberSelect(selectedMember)
                                        }
                                        true
                                    } else if (textFieldValue.text.isNotBlank()) {
                                        showEmojiPicker = false
                                        onSendMessage()
                                        true
                                    } else {
                                        true // Consume to prevent accidental newline on empty field
                                    }
                                }
                                // Tab also selects mention if popup is open
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Tab &&
                                showMentionPopup &&
                                filteredMembers.isNotEmpty() -> {
                                    val selectedMember = filteredMembers.getOrNull(mentionSelectedIndex)
                                    if (selectedMember != null) {
                                        handleMemberSelect(selectedMember)
                                    }
                                    true
                                }
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
                    maxLines = 4,
                    visualTransformation = MentionVisualTransformation(
                        mentionedNames = mentions.keys,
                        mentionColor = NostrordColors.MentionText,
                        emojiFontFamily = rememberEmojiFontFamily()
                    )
                )

                // Emoji picker button — desktop/web only
                if (showEmojiButton) {
                    IconButton(
                        onClick = {
                            showEmojiPicker = !showEmojiPicker
                            if (showEmojiPicker) showMentionPopup = false
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.EmojiEmotions,
                            contentDescription = "Emoji picker",
                            tint = if (showEmojiPicker) NostrordColors.Primary
                                   else NostrordColors.TextMuted,
                            modifier = Modifier.size(Spacing.iconMd)
                        )
                    }
                }

                // Send button — disabled when empty, shows spinner while sending
                IconButton(
                    onClick = {
                        if (textFieldValue.text.isNotBlank() && !isSending) {
                            showEmojiPicker = false
                            onSendMessage()
                        }
                    },
                    enabled = textFieldValue.text.isNotBlank() && !isSending,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Spacing.iconMd),
                            color = NostrordColors.Primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            tint = if (textFieldValue.text.isNotBlank()) NostrordColors.Primary
                                   else NostrordColors.TextMuted,
                            modifier = Modifier.size(Spacing.iconMd)
                        )
                    }
                }
            }

            // Mention popup floating above the input
            if (showMentionPopup && groupMembers.isNotEmpty()) {
                val density = LocalDensity.current
                val filteredCount = getFilteredMembers(groupMembers, mentionQuery).size
                // Calculate popup height in dp: header (~28dp) + divider + items (each ~36dp), max 8 items
                val popupHeightDp = 28 + 2 + (filteredCount.coerceAtMost(8) * 36)
                val popupHeightPx = with(density) { popupHeightDp.dp.roundToPx() }
                val offsetXPx = with(density) { Spacing.lg.roundToPx() }

                // Fullscreen scrim to capture clicks outside popup and TextField
                Popup(
                    alignment = Alignment.Center,
                    onDismissRequest = { showMentionPopup = false }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showMentionPopup = false }
                            )
                    )
                }

                // The actual mention popup
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(
                        x = offsetXPx,
                        y = -popupHeightPx
                    ),
                    onDismissRequest = { showMentionPopup = false },
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnClickOutside = false,
                        dismissOnBackPress = true
                    )
                ) {
                    MentionPopup(
                        members = groupMembers,
                        query = mentionQuery,
                        selectedIndex = mentionSelectedIndex,
                        onMemberSelect = { handleMemberSelect(it) }
                    )
                }
            }

            // Emoji picker popup — single fullscreen popup with scrim + positioned picker
            if (showEmojiPicker) {
                Popup(
                    alignment = Alignment.Center,
                    onDismissRequest = {
                        showEmojiPicker = false
                        focusRequester.requestFocus()
                    },
                    properties = PopupProperties(
                        focusable = true,
                        dismissOnClickOutside = false,
                        dismissOnBackPress = true
                    )
                ) {
                    // Fullscreen container: transparent scrim catches clicks outside picker
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    showEmojiPicker = false
                                    focusRequester.requestFocus()
                                }
                            )
                    ) {
                        // Picker anchored to bottom-end, above the input bar
                        EmojiPicker(
                            onEmojiSelect = { emoji ->
                                val text = textFieldValue.text
                                val cursor = textFieldValue.selection.start
                                val newText = text.substring(0, cursor) + emoji + text.substring(cursor)
                                val newCursor = cursor + emoji.length
                                textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                                onMessageInputChange(newText)
                            },
                            onDismiss = {
                                showEmojiPicker = false
                                focusRequester.requestFocus()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = Spacing.lg, bottom = 56.dp)
                        )
                    }
                }
            }
            } // End Box
        } // End Column
    }
}

/**
 * Compact bar shown above input when replying to a message.
 */
@Composable
private fun ReplyingToBar(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata?,
    userMetadata: Map<String, UserMetadata>,
    onCancelReply: () -> Unit
) {
    val authorName = metadata?.displayName
        ?: metadata?.name
        ?: message.pubkey.take(8) + "..."

    // Request metadata for any pubkeys mentioned in the content
    LaunchedEffect(message.content) {
        val pubkeysToFetch = org.nostr.nostrord.ui.components.chat.extractPubkeysFromContent(message.content)
            .filter { !userMetadata.containsKey(it) }
            .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            org.nostr.nostrord.di.AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    // Process mentions in content to show @name instead of nostr:npub...
    val processedContent = remember(message.content, userMetadata) {
        org.nostr.nostrord.ui.components.chat.processMentionsInContent(message.content, userMetadata)
            .replace('\n', ' ')
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NostrordColors.Surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    color = NostrordColors.Primary,
                    shape = RoundedCornerShape(1.5.dp)
                )
        )

        Spacer(modifier = Modifier.width(Spacing.sm))

        // Reply info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Replying to $authorName",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption
            )
            Text(
                text = processedContent.take(50) + if (processedContent.length > 50) "..." else "",
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
                maxLines = 1
            )
        }

        // Cancel button
        IconButton(
            onClick = onCancelReply,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel reply",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Regex matching emoji codepoints — same pattern used in MessageContent for display.
 */
private val emojiRegex = Regex(
    "[" +
        "\u00A9\u00AE" +
        "\u200D" +
        "\u203C\u2049" +
        "\u2122\u2139" +
        "\u2194-\u21AA" +
        "\u231A-\u23FF" +
        "\u2460-\u24FF" +
        "\u25AA-\u27BF" +
        "\u2934-\u2935" +
        "\u2B05-\u2B55" +
        "\u3030\u303D\u3297\u3299" +
        "\uD83C\uDC04-\uD83D\uDEFF" +
        "\uD83E\uDD00-\uD83E\uDDFF" +
        "\uD83E\uDE00-\uD83E\uDEFF" +
        "\uD83C\uDDE6-\uD83C\uDDFF" +
        "\uD83C\uDF00-\uD83C\uDFFF" +
        "\uD83D\uDE00-\uD83D\uDE4F" +
        "\uD83D\uDE80-\uD83D\uDEFF" +
        "\uFE0E\uFE0F" +
        "\u20E3" +
        "]+"
)

/**
 * Visual transformation that highlights @mentions and applies
 * NotoColorEmoji font selectively to emoji segments.
 */
private class MentionVisualTransformation(
    private val mentionedNames: Set<String>,
    private val mentionColor: Color,
    private val emojiFontFamily: FontFamily? = null
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)

        // Find and highlight @mentions
        mentionedNames.forEach { displayName ->
            val mentionText = "@$displayName"
            var startIndex = 0
            while (true) {
                val index = text.text.indexOf(mentionText, startIndex)
                if (index == -1) break
                builder.addStyle(
                    SpanStyle(color = mentionColor),
                    index,
                    index + mentionText.length
                )
                startIndex = index + mentionText.length
            }
        }

        // Apply emoji font only to emoji segments
        if (emojiFontFamily != null) {
            emojiRegex.findAll(text.text).forEach { match ->
                builder.addStyle(
                    SpanStyle(fontFamily = emojiFontFamily),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
