package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.io.FilenameFilter
import javax.swing.SwingUtilities

actual class MediaPickerLauncher(
    private val doLaunch: () -> Unit,
) {
    actual fun launch() = doLaunch()
}

private val IMAGE_EXTENSIONS = arrayOf("jpg", "jpeg", "png", "gif", "webp", "avif")
private val AUDIO_EXTENSIONS = arrayOf("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus")
private val ALL_EXTENSIONS = IMAGE_EXTENSIONS + arrayOf("mp4", "mov", "webm") + AUDIO_EXTENSIONS
private val ALL_EXTENSIONS_SET = ALL_EXTENSIONS.toSet()
private val IMAGE_EXTENSIONS_SET = IMAGE_EXTENSIONS.toSet()

/**
 * Uses the native [FileDialog] (GTK file chooser on Linux) owned by the Compose window.
 * The OS manages the dialog, so it can't fall behind the app and focus returns to the
 * owner window on close — unlike a Swing JFileChooser in a throwaway JDialog, which left
 * focus on a background window on some Linux WMs. Extension filtering is best-effort via
 * FilenameFilter; the selection is validated again after the dialog closes.
 */
@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onPickStart: () -> Unit,
    onError: (String) -> Unit,
    onFilePicked: (ByteArray, String) -> Unit,
): MediaPickerLauncher {
    val scope = rememberCoroutineScope()
    // The launcher is remembered once, so capture the latest callbacks via
    // rememberUpdatedState (like the Android picker). Without this the picker keeps
    // the first composition's onFilePicked, which closes over a stale textFieldValue
    // state — the upload then writes the URL to an orphaned field and the visible
    // input never updates after a recomposition (e.g. switching groups).
    val currentOnPickStart = rememberUpdatedState(onPickStart)
    val currentOnError = rememberUpdatedState(onError)
    val currentOnFilePicked = rememberUpdatedState(onFilePicked)
    val allowedSet =
        when (accept) {
            MediaAccept.Images -> IMAGE_EXTENSIONS_SET
            MediaAccept.ImagesVideosAudio -> ALL_EXTENSIONS_SET
        }
    return remember {
        MediaPickerLauncher {
            SwingUtilities.invokeLater {
                val owner =
                    KeyboardFocusManager
                        .getCurrentKeyboardFocusManager()
                        .activeWindow as? Frame

                val dialog =
                    FileDialog(owner, "Select File", FileDialog.LOAD).apply {
                        isMultipleMode = false
                        // Honored by the GTK chooser; ignored on some platforms, where the
                        // post-selection extension check below enforces the same rule.
                        filenameFilter =
                            FilenameFilter { _, name ->
                                name.substringAfterLast('.', "").lowercase() in allowedSet
                            }
                    }

                dialog.isVisible = true // blocks the EDT until the native dialog closes

                val name = dialog.file ?: return@invokeLater // cancelled
                val dir = dialog.directory ?: return@invokeLater
                val file = File(dir, name)
                val ext = file.extension.lowercase()

                when {
                    ext !in allowedSet ->
                        currentOnError.value(
                            "\".${ext.ifEmpty { "unknown" }}\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE",
                        )
                    file.length() > MAX_UPLOAD_BYTES ->
                        currentOnError.value("File is too large. The maximum upload size is 20 MB.")
                    else -> {
                        currentOnPickStart.value()
                        scope.launch(Dispatchers.IO) {
                            val bytes = file.readBytes()
                            withContext(Dispatchers.Main) { currentOnFilePicked.value(bytes, file.name) }
                        }
                    }
                }
            }
        }
    }
}
