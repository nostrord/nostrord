package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

private val IMAGE_EXTENSIONS     = arrayOf("jpg", "jpeg", "png", "gif", "webp")
private val AUDIO_EXTENSIONS     = arrayOf("mp3", "ogg", "wav", "flac", "m4a", "aac")
private val ALL_EXTENSIONS       = IMAGE_EXTENSIONS + arrayOf("mp4", "mov") + AUDIO_EXTENSIONS
private val ALL_EXTENSIONS_SET   = ALL_EXTENSIONS.toSet()
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
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    val scope = rememberCoroutineScope()
    return remember {
        MediaPickerLauncher {
            val deferred = CompletableDeferred<Pair<ByteArray, String>?>()

            SwingUtilities.invokeLater {
                if (!systemLafApplied) {
                    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                    systemLafApplied = true
                }

                val appWindow: Window? = KeyboardFocusManager
                    .getCurrentKeyboardFocusManager()
                    .activeWindow

                val appBounds: Rectangle = appWindow?.bounds
                    ?: Toolkit.getDefaultToolkit().screenSize
                        .let { Rectangle(0, 0, it.width, it.height) }

                val monitorBounds: Rectangle = appWindow
                    ?.graphicsConfiguration?.bounds
                    ?: appBounds

                val anchor = JFrame().apply {
                    isUndecorated = true
                    isAlwaysOnTop = true
                    setSize(1, 1)
                    setLocation(
                        appBounds.x + appBounds.width / 2,
                        appBounds.y + appBounds.height / 2
                    )
                    isVisible = true
                }

                val (filterDesc, extensions, allowedSet) = when (accept) {
                    MediaAccept.Images ->
                        Triple("Images (jpg, png, gif, webp)", IMAGE_EXTENSIONS, IMAGE_EXTENSIONS_SET)
                    MediaAccept.ImagesVideosAudio ->
                        Triple("Images, Videos & Audio", ALL_EXTENSIONS, ALL_EXTENSIONS_SET)
                }

                val chooser = JFileChooser().apply {
                    dialogTitle = "Select File"
                    fileFilter = FileNameExtensionFilter(filterDesc, *extensions)
                    isMultiSelectionEnabled = false
                    fileSelectionMode = JFileChooser.FILES_ONLY
                }

                val pickerDialog = JDialog(
                    anchor, "Select File",
                    Dialog.ModalityType.APPLICATION_MODAL
                ).apply {
                    isAlwaysOnTop = true
                    defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                    contentPane.add(chooser)
                    pack()

                    val dw = width
                    val dh = height
                    var dx = appBounds.x + (appBounds.width - dw) / 2
                    var dy = appBounds.y + (appBounds.height - dh) / 2
                    dx = dx.coerceIn(
                        monitorBounds.x,
                        monitorBounds.x + (monitorBounds.width - dw).coerceAtLeast(0)
                    )
                    dy = dy.coerceIn(
                        monitorBounds.y,
                        monitorBounds.y + (monitorBounds.height - dh).coerceAtLeast(0)
                    )
                    setLocation(dx, dy)
                }

                chooser.addActionListener { e ->
                    when (e.actionCommand) {
                        JFileChooser.APPROVE_SELECTION,
                        JFileChooser.CANCEL_SELECTION -> pickerDialog.dispose()
                    }
                }

                try {
                    pickerDialog.isVisible = true // blocks EDT until dialog is disposed

                    val selected = chooser.selectedFile
                    if (selected != null && selected.extension.lowercase() in allowedSet) {
                        scope.launch(Dispatchers.IO) {
                            deferred.complete(selected.readBytes() to selected.name)
                        }
                    } else {
                        deferred.complete(null)
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
                        withContext(Dispatchers.Main) { onFilePicked(result.first, result.second) }
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}
