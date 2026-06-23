package org.nostr.nostrord.ui.screens.dm
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.FileTooLargeException
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.NostrBuildUploader
import org.nostr.nostrord.network.upload.PasteMediaEffect
import org.nostr.nostrord.network.upload.UnsupportedFileTypeException
import org.nostr.nostrord.network.upload.rememberClipboardImageReader
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.layout.DmConversationList
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Direct-message conversation page (prototype DirectMessage, NIP-17 style). The
 * message backend does not exist yet: the conversation intro and the composer are
 * in place, with sending disabled until NIP-17 lands. Mirrors the web
 * web/screens/DmPage.
 */
@Composable
fun DmPageScreen(
    pubkey: String?,
    onOpenProfile: (UserRoute) -> Unit,
    onOpenConversation: (DmRoute) -> Unit = {},
    // Non-null only on compact/mobile (sidebar is in the drawer). Drives the hamburger and, on the
    // empty landing, the conversation list shown in the page body (no visible DM sidebar there),
    // mirroring the web `.dm-page-convos` media query.
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        if (pubkey == null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                onOpenDrawer?.let { open ->
                    IconButton(onClick = open, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open menu",
                            tint = NostrordColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    "Direct messages",
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = NostrordColors.Divider)
            if (onOpenDrawer != null) {
                // Compact / mobile: the DM sidebar is in the drawer, so show the conversation list
                // in the page body (web `.dm-page-convos`). The empty-state CTA opens the drawer,
                // where search starts a new conversation.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    DmConversationList(
                        onOpenConversation = onOpenConversation,
                        onStartConversation = onOpenDrawer,
                    )
                }
            } else {
                // Desktop: the conversation list lives in the sidebar, so the main area is the hero.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier =
                        Modifier
                            .size(64.dp)
                            .clip(NostrordShapes.shapeXLarge)
                            .background(NostrordColors.BackgroundFloating),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✉️", fontSize = 30.sp)
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        "Your direct messages",
                        color = NostrordColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        "Pick a conversation on the side or start a new one with someone you follow.",
                        color = NostrordColors.TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
            }
            return@Column
        }

        val vm = viewModel(key = "dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata by vm.metadata.collectAsState()
        val dmVm = viewModel { DmViewModel(AppModule.nostrRepository) }
        val messagesByPeer by dmVm.messagesByPeer.collectAsState()
        val messages = messagesByPeer[pubkey].orEmpty()
        // Mark the conversation read while it is open (and as new messages stream in).
        LaunchedEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        // Composer state mirrors the group MessageInput (minus mentions / formatting): a
        // TextFieldValue for caret-aware emoji/paste insertion, plus paste-upload + emoji.
        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        var showEmojiPicker by remember { mutableStateOf(false) }
        var isUploadingPaste by remember { mutableStateOf(false) }
        var pasteError by remember { mutableStateOf<String?>(null) }
        val focusRequester = remember { FocusRequester() }
        val clipboardReader = rememberClipboardImageReader()
        val scope = rememberCoroutineScope()

        val send = {
            val body = textFieldValue.text.trim()
            if (body.isNotBlank()) {
                dmVm.send(pubkey, body)
                textFieldValue = TextFieldValue("")
            }
        }

        fun appendUploadedUrl(url: String) {
            val current = textFieldValue.text
            val sep = if (current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("\n")) " " else ""
            val newText = current + sep + url
            textFieldValue = TextFieldValue(newText, TextRange(newText.length))
        }

        suspend fun handlePastedMedia(bytes: ByteArray, filename: String) {
            if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
                isUploadingPaste = false
                pasteError = "This file is too large. The maximum upload size is 20 MB."
                return
            }
            try {
                val mime = NostrBuildUploader.mimeTypeForFilename(filename)
                when (val result = NostrBuildUploader.upload(bytes, filename, mime, AppModule.nostrRepository::buildNip98AuthHeader)) {
                    is Result.Success -> appendUploadedUrl(result.data.url)
                    is Result.Error -> pasteError = result.error.message
                }
            } finally {
                isUploadingPaste = false
            }
        }

        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            onOpenDrawer?.let { open ->
                IconButton(onClick = open, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open menu",
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeSmall)
                    .clickable { onOpenProfile(UserRoute(pubkey)) }
                    .padding(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OptimizedSmallAvatar(
                    imageUrl = metadata?.picture,
                    identifier = pubkey,
                    displayName = name,
                    size = 24.dp,
                    shape = CircleShape,
                )
                Text(
                    name,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.BackgroundFloating) {
                Text(
                    "DM · encrypted",
                    color = NostrordColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar + name open the peer's profile, like the header peer button.
            OptimizedSmallAvatar(
                imageUrl = metadata?.picture,
                identifier = pubkey,
                displayName = name,
                size = 64.dp,
                shape = CircleShape,
                modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(UserRoute(pubkey)) },
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                name,
                color = NostrordColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeSmall)
                    .clickable { onOpenProfile(UserRoute(pubkey)) }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            )
            Text(
                "Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17).",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            messages.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = NostrordShapes.shapeMedium,
                        color = if (m.mine) NostrordColors.Primary else NostrordColors.BackgroundFloating,
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Text(
                            m.content,
                            color = if (m.mine) Color.White else NostrordColors.TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        )
                    }
                }
            }
        }

        // Single rounded "pill" like the web DM composer / group composer (web .composer parity).
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl, top = Spacing.xs)) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(NostrordShapes.inputShape)
                    .background(NostrordColors.SurfaceVariant)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageUploadButton(
                    externalBusy = isUploadingPaste,
                    onUploadComplete = { uploadResult -> appendUploadedUrl(uploadResult.url) },
                )
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    cursorBrush = SolidColor(Color.White),
                    textStyle = NostrordTypography.Input.copy(color = Color.White),
                    maxLines = 7,
                    modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown && event.key == Key.Escape && showEmojiPicker -> {
                                    showEmojiPicker = false
                                    true
                                }
                                // Ctrl+V media: read the clipboard image and upload it.
                                event.type == KeyEventType.KeyDown && event.key == Key.V && event.isCtrlPressed && !isUploadingPaste -> {
                                    val hasMedia = runCatching { clipboardReader.hasImage() }.getOrDefault(false)
                                    if (hasMedia) {
                                        isUploadingPaste = true
                                        scope.launch {
                                            val image =
                                                try {
                                                    clipboardReader.read()
                                                } catch (e: FileTooLargeException) {
                                                    isUploadingPaste = false
                                                    pasteError = e.message
                                                    return@launch
                                                } catch (e: UnsupportedFileTypeException) {
                                                    isUploadingPaste = false
                                                    pasteError = e.message
                                                    return@launch
                                                }
                                            if (image == null) {
                                                isUploadingPaste = false
                                                return@launch
                                            }
                                            handlePastedMedia(image.first, image.second)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed -> {
                                    val sel = textFieldValue.selection
                                    val t = textFieldValue.text
                                    val newText = t.substring(0, sel.start) + "\n" + t.substring(sel.end)
                                    textFieldValue = TextFieldValue(newText, TextRange(sel.start + 1))
                                    true
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed -> {
                                    if (textFieldValue.text.isNotBlank()) send()
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(vertical = 4.dp)) {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    "Message $name",
                                    style = NostrordTypography.InputPlaceholder,
                                    color = NostrordColors.TextMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = { showEmojiPicker = !showEmojiPicker },
                    modifier = Modifier.size(width = 26.dp, height = 32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = "Emoji",
                        tint = if (showEmojiPicker) NostrordColors.Primary else NostrordColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = send,
                    enabled = textFieldValue.text.isNotBlank() && !isUploadingPaste,
                    modifier = Modifier.size(width = 26.dp, height = 32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (textFieldValue.text.isNotBlank()) NostrordColors.Primary else NostrordColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showEmojiPicker) {
                Popup(
                    alignment = Alignment.BottomEnd,
                    onDismissRequest = { showEmojiPicker = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showEmojiPicker = false },
                            ),
                    ) {
                        EmojiPicker(
                            onEmojiSelect = { emoji ->
                                val t = textFieldValue.text
                                val cursor = textFieldValue.selection.start
                                val newText = t.substring(0, cursor) + emoji + t.substring(cursor)
                                textFieldValue = TextFieldValue(newText, TextRange(cursor + emoji.length))
                            },
                            onDismiss = { showEmojiPicker = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.lg, bottom = 56.dp),
                        )
                    }
                }
            }
        }

        // Desktop / Android paste of media (web is a no-op here, handled in the JS composer).
        PasteMediaEffect(
            onMediaPasted = { bytes, filename ->
                if (!isUploadingPaste) {
                    isUploadingPaste = true
                    scope.launch { handlePastedMedia(bytes, filename) }
                }
            },
            onError = { pasteError = it },
        )

        pasteError?.let { error ->
            AlertDialog(
                onDismissRequest = { pasteError = null },
                title = { Text("Upload Failed") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { pasteError = null }) { Text("OK") } },
            )
        }
    }
}
