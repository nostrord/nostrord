package org.nostr.nostrord.web.upload

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.NostrBuildUploader
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.w3c.files.Blob
import org.w3c.files.FileReader
import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.useState
import web.cssom.ClassName
import web.file.File
import web.html.InputType
import web.html.file
import kotlin.coroutines.resume

private suspend fun readBytes(file: File): ByteArray =
    suspendCancellableCoroutine { cont ->
        val reader = FileReader()
        reader.onload = {
            val buffer = reader.result.unsafeCast<ArrayBuffer>()
            val int8 = Int8Array(buffer)
            cont.resume(ByteArray(int8.length) { int8[it] })
            null
        }
        reader.onerror = {
            cont.resume(ByteArray(0))
            null
        }
        reader.readAsArrayBuffer(file.unsafeCast<Blob>())
    }

/** Upload a picked web File to nostr.build (NIP-98). Returns the URL, or null on failure. */
suspend fun uploadWebFile(file: File): String? {
    val bytes = readBytes(file)
    if (bytes.isEmpty()) return null
    val mime = NostrBuildUploader.mimeTypeForFilename(file.name)
    val result = NostrBuildUploader.upload(bytes, file.name, mime, AppModule.nostrRepository::buildNip98AuthHeader)
    return (result as? Result.Success)?.data?.url
}

external interface UploadButtonProps : Props {
    var label: String
    var accept: String?
    var onUploaded: (String) -> Unit
}

/**
 * Reusable file-upload button (a label wrapping a hidden file input). Picks a file,
 * uploads to nostr.build, and calls [UploadButtonProps.onUploaded] with the URL.
 * Used for profile avatar/banner, group picture, and the chat composer.
 */
val UploadButton =
    FC<UploadButtonProps> { props ->
        val (busy, setBusy) = useState { false }
        label {
            className = ClassName("upload-btn")
            +(if (busy) "Uploading…" else props.label)
            input {
                className = ClassName("hidden-file-input")
                type = InputType.file
                accept = props.accept
                disabled = busy
                onChange = { event ->
                    event.currentTarget.files?.item(0)?.let { picked ->
                        setBusy(true)
                        launchApp {
                            val url = uploadWebFile(picked)
                            setBusy(false)
                            if (url != null) props.onUploaded(url)
                        }
                    }
                }
            }
        }
    }
