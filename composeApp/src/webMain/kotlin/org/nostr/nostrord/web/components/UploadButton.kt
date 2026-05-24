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

    /** Idle label/icon text. */
    var text: String
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
            +(if (busy) "…" else props.text)
            input {
                className = ClassName("upload-file-input")
                type = InputType.file
                accept = "image/*"
                disabled = busy
                onChange = { event ->
                    val target = event.currentTarget
                    val f = target.asDynamic().files?.get(0)
                    if (f != null) {
                        setBusy(true)
                        launchApp {
                            try {
                                val bytes = readFileBytes(f)
                                val mime = (f.type.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                                val name = f.name.unsafeCast<String?>() ?: "image"
                                if (bytes.isNotEmpty()) {
                                    val result =
                                        NostrBuildUploader.upload(
                                            bytes,
                                            name,
                                            mime,
                                            AppModule.nostrRepository::buildNip98AuthHeader,
                                        )
                                    if (result is Result.Success) props.onUploaded(result.data.url)
                                }
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
