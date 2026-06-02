package org.nostr.nostrord.web.components

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES
import org.nostr.nostrord.network.upload.NostrBuildUploader
import org.nostr.nostrord.network.upload.SUPPORTED_FORMATS_MESSAGE
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.upload.isSupportedUploadMime
import org.nostr.nostrord.utils.AppError
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
    /** Called with the upload result (URL + NIP-68 metadata) on success. */
    var onUploaded: (UploadResult) -> Unit

    /** Called with a user-facing message when validation or the upload fails. */
    var onError: (String) -> Unit

    /** Class for the clickable label (e.g. "composer-btn" / "upload-btn"). */
    var cls: String

    /** Idle icon shown when not uploading. */
    var icon: Ic
}

/**
 * Upload button — a styled `<label>` wrapping a hidden file input. On pick it validates
 * and reads the file bytes, uploads to nostr.build (NIP-98 auth via the repository), then
 * hands back the full result. Shows "…" while in flight. Failures (too large, unsupported
 * format, auth/server/network) are reported via [UploadButtonProps.onError] instead of
 * being swallowed silently, matching the native picker.
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
                accept = "image/*,video/*,audio/*"
                disabled = busy
                onChange = { event ->
                    val target = event.currentTarget
                    val fileList = target.asDynamic().files
                    val f = if (fileList != null && (fileList.length as Int) > 0) fileList[0] else null
                    if (f != null) {
                        setBusy(true)
                        launchApp {
                            try {
                                when (val r = uploadBlob(f)) {
                                    is Result.Success -> props.onUploaded(r.data)
                                    is Result.Error -> props.onError(r.error.message)
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

/**
 * Validate and upload a picked/pasted File (or Blob) to nostr.build (NIP-98 auth via the
 * repository). Mirrors the native picker's checks: the 20 MB cap and the supported
 * image/video/audio formats, with the same user-facing messages. Returns the full
 * [UploadResult] (URL + NIP-68 metadata) so callers can build imeta tags, or a
 * [Result.Error] to surface. Reusable by the file picker and Ctrl+V paste / drag-and-drop.
 */
suspend fun uploadBlob(file: dynamic): Result<UploadResult> {
    val name = (file.name.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "file"
    val size = (file.size.unsafeCast<Double?>())?.toLong() ?: 0L
    if (size > MAX_UPLOAD_BYTES) {
        return Result.Error(AppError.Unknown("File is too large. The maximum upload size is 20 MB."))
    }
    // Prefer the extension-derived mime (parity with native); fall back to the
    // browser-provided type for pasted blobs that arrive without a real filename.
    val byName = NostrBuildUploader.mimeTypeForFilename(name)
    val mime =
        if (byName != "application/octet-stream") {
            byName
        } else {
            (file.type.unsafeCast<String?>())?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        }
    if (!isSupportedUploadMime(mime)) {
        val ext = name.substringAfterLast('.', "").ifEmpty { "unknown" }
        return Result.Error(AppError.Unknown("\".$ext\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE"))
    }
    val bytes = readFileBytes(file)
    if (bytes.isEmpty()) return Result.Error(AppError.Unknown("Could not read the selected file."))
    return NostrBuildUploader.upload(
        bytes,
        name,
        mime,
        AppModule.nostrRepository::buildNip98AuthHeader,
    )
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
