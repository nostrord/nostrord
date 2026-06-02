package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File

actual class MediaPickerLauncher(
    private val doLaunch: () -> Unit,
) {
    actual fun launch() = doLaunch()
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")
private val ALL_EXTENSIONS =
    IMAGE_EXTENSIONS + setOf(
        "mp4", "mov", "webm",
        "mp3", "ogg", "wav", "flac", "m4a", "aac", "opus",
    )

/**
 * Native file picker via LWJGL tinyfd, which on Linux shells out to zenity/kdialog as a
 * subprocess. That subprocess window is managed by the WM independently of the JVM, so it
 * always opens in front and focus returns to the app on close — no AWT `_NET_WM_USER_TIME`
 * focus-stealing dance (java.awt.FileDialog) and no dbus-java on the classpath (FileKit),
 * which would clash with java-keyring's Secret Service and break the OS keychain.
 */
@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onPickStart: () -> Unit,
    onError: (String) -> Unit,
    onFilePicked: (ByteArray, String) -> Unit,
): MediaPickerLauncher {
    val scope = rememberCoroutineScope()
    // Capture the latest callbacks: the launcher is remembered once, and a stale
    // onFilePicked would write the uploaded URL into an orphaned composer field.
    val currentOnPickStart = rememberUpdatedState(onPickStart)
    val currentOnError = rememberUpdatedState(onError)
    val currentOnFilePicked = rememberUpdatedState(onFilePicked)
    val allowedSet =
        when (accept) {
            MediaAccept.Images -> IMAGE_EXTENSIONS
            MediaAccept.ImagesVideosAudio -> ALL_EXTENSIONS
        }

    return remember {
        MediaPickerLauncher {
            // tinyfd blocks (waits on the zenity subprocess), so run it off the UI thread.
            scope.launch(Dispatchers.IO) {
                val path = openNativeFileDialog(allowedSet) ?: return@launch // cancelled
                val file = File(path)
                val ext = file.extension.lowercase()
                when {
                    ext !in allowedSet ->
                        withContext(Dispatchers.Main) {
                            currentOnError.value(
                                "\".${ext.ifEmpty { "unknown" }}\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE",
                            )
                        }
                    file.length() > MAX_UPLOAD_BYTES ->
                        withContext(Dispatchers.Main) {
                            currentOnError.value("File is too large. The maximum upload size is 20 MB.")
                        }
                    else -> {
                        withContext(Dispatchers.Main) { currentOnPickStart.value() }
                        val bytes = file.readBytes()
                        withContext(Dispatchers.Main) { currentOnFilePicked.value(bytes, file.name) }
                    }
                }
            }
        }
    }
}

/** Open the native single-file picker, restricted to [allowedSet] extensions. Returns the
 *  chosen path, or null if cancelled. Blocks until the dialog closes. */
private fun openNativeFileDialog(allowedSet: Set<String>): String? {
    MemoryStack.stackPush().use { stack ->
        val patterns = stack.mallocPointer(allowedSet.size)
        allowedSet.forEach { patterns.put(stack.UTF8("*.$it")) }
        patterns.flip()
        return TinyFileDialogs.tinyfd_openFileDialog(
            "Select File",
            null,
            patterns,
            "Images, videos & audio",
            false,
        )
    }
}
