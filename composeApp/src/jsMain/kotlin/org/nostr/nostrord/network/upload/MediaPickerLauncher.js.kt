package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import org.w3c.files.get

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    val currentCallback = rememberUpdatedState(onFilePicked)
    val acceptAttr = when (accept) {
        MediaAccept.Images            -> "image/*"
        MediaAccept.ImagesVideosAudio -> "image/*,video/mp4,video/quicktime,audio/*"
    }
    return remember(acceptAttr) {
        MediaPickerLauncher {
            val input = document.createElement("input") as HTMLInputElement
            input.type = "file"
            input.accept = acceptAttr
            input.style.cssText = "display:none;position:fixed;top:-9999px;left:-9999px"

            fun cleanup() {
                try { document.body?.removeChild(input) } catch (_: Throwable) {}
            }

            input.addEventListener("cancel", { cleanup() })

            input.onchange = {
                val file = input.files?.get(0)
                if (file != null) {
                    val reader = FileReader()
                    reader.onload = {
                        val buffer = reader.result as ArrayBuffer
                        val int8 = Int8Array(buffer)
                        // Copy via asDynamic() — Int8Array.get(index) operator resolution
                        // is unreliable across Kotlin/JS versions; unsafeCast<ByteArray>()
                        // may produce a raw JS object that Ktor's multipart writer rejects.
                        val bytes = ByteArray(int8.length).also { arr ->
                            for (i in arr.indices) arr[i] = int8.asDynamic()[i]
                        }
                        currentCallback.value(bytes, file.name)
                        cleanup()
                    }
                    reader.onerror = { cleanup() }
                    reader.readAsArrayBuffer(file)
                } else {
                    cleanup()
                }
            }

            document.body?.appendChild(input)
            input.click()
        }
    }
}
