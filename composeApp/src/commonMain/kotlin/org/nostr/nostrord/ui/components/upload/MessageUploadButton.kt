package org.nostr.nostrord.ui.components.upload

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }

    val picker = rememberMediaPickerLauncher(accept = MediaAccept.ImagesVideosAudio) { bytes, filename ->
        isUploading = true
        scope.launch {
            try {
                val mime = NostrBuildUploader.mimeTypeForFilename(filename)
                val result = NostrBuildUploader.upload(
                    bytes, filename, mime,
                    AppModule.nostrRepository::buildNip98AuthHeader
                )
                if (result is Result.Success) onUploadComplete(result.data)
            } finally {
                isUploading = false
            }
        }
    }

    IconButton(
        onClick = { picker.launch() },
        enabled = !isUploading,
        modifier = modifier
            .size(40.dp)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = NostrordColors.Primary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attach image",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
