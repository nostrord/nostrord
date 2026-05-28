package org.nostr.nostrord.web.components

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.NostrBuildUploader
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.file
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

external interface UploadButtonProps : Props {
    /** Called with the uploaded media URL on success. */
    var onUploaded: (String) -> Unit

    /** Class for the clickable label (e.g. "composer-btn" / "upload-btn"). */
    var cls: String

    /** Idle icon shown when not uploading. */
    var icon: Ic
}

/**
 * Upload button — a styled `<label>` wrapping a hidden file input. On pick it reads the
 * file bytes and uploads to nostr.build (NIP-98 auth via the repository), then hands back
 * the resulting URL. Shows "…" while in flight.
 */
val UploadButton =
    FC<UploadButtonProps> { props ->
        val (busy, setBusy) = useState { false }

        label {
            className = ClassName(props.cls)
            if (busy) +"…" else icon(props.icon)
            input {
                className = ClassName("upload-file-input")
                type = InputType.file
                accept = "image/*"
                disabled = busy
                onChange = { event ->
                    val target = event.currentTarget
                    val fileList = target.asDynamic().files
                    val f = if (fileList != null && (fileList.length as Int) > 0) fileList[0] else null
                    if (f != null) {
                        setBusy(true)
                        launchApp {
                            try {
                                val url = uploadBlob(f)
                                if (url != null) props.onUploaded(url)
                            } finally {
                                setBusy(false)
                                target.asDynamic().value = ""
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * Upload a picked/pasted File (or Blob) to nostr.build (NIP-98 auth via the repository).
 * Returns the media URL, or null on failure. Reusable by the file picker and Ctrl+V paste.
 */
suspend fun uploadBlob(file: dynamic): String? {
    val bytes = readFileBytes(file)
    if (bytes.isEmpty()) return null
    val mime = (file.type.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "image/jpeg"
    val name = (file.name.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "image"
    val result =
        NostrBuildUploader.upload(
            bytes,
            name,
            mime,
            AppModule.nostrRepository::buildNip98AuthHeader,
        )
    return if (result is Result.Success) result.data.url else null
}

/** Read a picked file's bytes via the Blob arrayBuffer() promise. */
private suspend fun readFileBytes(file: dynamic): ByteArray = suspendCoroutine { cont ->
    val promise = file.arrayBuffer()
    promise.then(
        { buffer: dynamic ->
            val arr = Int8Array(buffer.unsafeCast<ArrayBuffer>())
            cont.resume(ByteArray(arr.length) { arr[it] })
        },
        { _: dynamic ->
            cont.resume(ByteArray(0))
        },
    )
}
