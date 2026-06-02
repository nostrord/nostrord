package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

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

// UIManager is global state — apply system L&F only once.
private var systemLafApplied = false

/**
 * Uses JFileChooser inside a JDialog(APPLICATION_MODAL) so the JVM's EDT blocks
 * input to all other app windows, preventing z-order issues on Linux.
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
    return remember {
        MediaPickerLauncher {
            val deferred = CompletableDeferred<Pair<ByteArray, String>?>()

            SwingUtilities.invokeLater {
                if (!systemLafApplied) {
                    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                    systemLafApplied = true
                }

                val appWindow: Window? =
                    KeyboardFocusManager
                        .getCurrentKeyboardFocusManager()
                        .activeWindow

                val appBounds: Rectangle =
                    appWindow?.bounds
                        ?: Toolkit
                            .getDefaultToolkit()
                            .screenSize
                            .let { Rectangle(0, 0, it.width, it.height) }

                val monitorBounds: Rectangle =
                    appWindow
                        ?.graphicsConfiguration
                        ?.bounds
                        ?: appBounds

                val anchor =
                    JFrame().apply {
                        isUndecorated = true
                        isAlwaysOnTop = true
                        setSize(1, 1)
                        setLocation(
                            appBounds.x + appBounds.width / 2,
                            appBounds.y + appBounds.height / 2,
                        )
                        isVisible = true
                    }

                val (filterDesc, extensions, allowedSet) =
                    when (accept) {
                        MediaAccept.Images ->
                            Triple("Images (jpg, png, gif, webp, avif)", IMAGE_EXTENSIONS, IMAGE_EXTENSIONS_SET)
                        MediaAccept.ImagesVideosAudio ->
                            Triple(
                                "Images, Videos & Audio (jpg, png, gif, webp, avif, mp4, mov, webm, mp3, ogg, wav, flac, m4a, aac, opus)",
                                ALL_EXTENSIONS,
                                ALL_EXTENSIONS_SET,
                            )
                    }

                val chooser =
                    JFileChooser().apply {
                        dialogTitle = "Select File"
                        fileFilter = FileNameExtensionFilter(filterDesc, *extensions)
                        isMultiSelectionEnabled = false
                        fileSelectionMode = JFileChooser.FILES_ONLY
                    }

                val pickerDialog =
                    JDialog(
                        anchor,
                        "Select File",
                        Dialog.ModalityType.APPLICATION_MODAL,
                    ).apply {
                        isAlwaysOnTop = true
                        defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                        contentPane.add(chooser)
                        pack()

                        val dw = width
                        val dh = height
                        var dx = appBounds.x + (appBounds.width - dw) / 2
                        var dy = appBounds.y + (appBounds.height - dh) / 2
                        dx =
                            dx.coerceIn(
                                monitorBounds.x,
                                monitorBounds.x + (monitorBounds.width - dw).coerceAtLeast(0),
                            )
                        dy =
                            dy.coerceIn(
                                monitorBounds.y,
                                monitorBounds.y + (monitorBounds.height - dh).coerceAtLeast(0),
                            )
                        setLocation(dx, dy)
                    }

                chooser.addActionListener { e ->
                    when (e.actionCommand) {
                        JFileChooser.APPROVE_SELECTION,
                        JFileChooser.CANCEL_SELECTION,
                        -> pickerDialog.dispose()
                    }
                }

                try {
                    pickerDialog.isVisible = true // blocks EDT until dialog is disposed

                    val selected = chooser.selectedFile
                    if (selected != null && selected.extension.lowercase() in allowedSet) {
                        scope.launch { currentOnPickStart.value() }
                        if (selected.length() > MAX_UPLOAD_BYTES) {
                            deferred.complete(null)
                            scope.launch { currentOnError.value("File is too large. The maximum upload size is 20 MB.") }
                        } else {
                            scope.launch(Dispatchers.IO) {
                                deferred.complete(selected.readBytes() to selected.name)
                            }
                        }
                    } else {
                        deferred.complete(null)
                        if (selected != null) {
                            val ext = selected.extension.ifEmpty { "unknown" }
                            scope.launch { currentOnError.value("\".$ext\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE") }
                        }
                    }
                } finally {
                    anchor.dispose()
                    appWindow?.requestFocus()
                }
            }

            scope.launch {
                try {
                    val result = deferred.await()
                    if (result != null) {
                        withContext(Dispatchers.Main) { currentOnFilePicked.value(result.first, result.second) }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }
}
