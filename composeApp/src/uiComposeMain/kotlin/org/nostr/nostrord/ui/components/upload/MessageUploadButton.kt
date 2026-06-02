package org.nostr.nostrord.ui.components.upload

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.MediaAccept
import org.nostr.nostrord.network.upload.NostrBuildUploader
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.upload.rememberMediaPickerLauncher
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.Result

/**
 * Attach/upload button for the message input row.
 * Picks a file, uploads to nostr.build, and returns the full upload result
 * (URL + NIP-68 metadata) so the caller can build imeta tags.
 */
@Composable
fun MessageUploadButton(
    onUploadComplete: (UploadResult) -> Unit,
    modifier: Modifier = Modifier,
    // Upload owned by the caller (paste / drag-and-drop) that doesn't go through
    // this button's picker. When true, the spinner shows here on the attach icon.
    externalBusy: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val busy = isUploading || externalBusy
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    val picker =
        rememberMediaPickerLauncher(
            accept = MediaAccept.ImagesVideosAudio,
            onPickStart = { isUploading = true },
            onError = {
                isUploading = false
                uploadError = it
            },
        ) { bytes, filename ->
            scope.launch {
                try {
                    val mime = NostrBuildUploader.mimeTypeForFilename(filename)
                    val result =
                        NostrBuildUploader.upload(
                            bytes,
                            filename,
                            mime,
                            AppModule.nostrRepository::buildNip98AuthHeader,
                        )
                    when (result) {
                        is Result.Success -> onUploadComplete(result.data)
                        is Result.Error -> uploadError = result.error.message
                    }
                } finally {
                    isUploading = false
                }
            }
        }

    uploadError?.let { error ->
        AlertDialog(
            onDismissRequest = { uploadError = null },
            title = { Text("Upload Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(
                    onClick = { uploadError = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = NostrordColors.Primary),
                ) { Text("OK") }
            },
        )
    }

    IconButton(
        onClick = { picker.launch() },
        enabled = !busy,
        interactionSource = interaction,
        modifier =
        modifier
            .size(32.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = NostrordColors.Primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attach image",
                // Hover brightens to TextContent (web .composer-btn:hover).
                tint = if (isHovered) NostrordColors.TextContent else NostrordColors.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
