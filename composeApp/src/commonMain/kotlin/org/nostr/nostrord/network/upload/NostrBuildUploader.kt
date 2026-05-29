package org.nostr.nostrord.network.upload

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

private const val UPLOAD_URL = "https://nostr.build/api/v2/upload/files"

const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

const val SUPPORTED_FORMATS_MESSAGE =
    "Supported formats:\n" +
        "Images: jpg, png, gif, webp, avif\n" +
        "Video: mp4, mov, webm\n" +
        "Audio: mp3, ogg, wav, flac, m4a, aac, opus"

internal fun isBlobRef(s: String) = s.startsWith("nostrord-blob|")

internal val SUPPORTED_UPLOAD_MIMES =
    setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/avif",
        "video/mp4",
        "video/quicktime",
        "video/webm",
        "audio/mpeg",
        "audio/ogg",
        "audio/wav",
        "audio/flac",
        "audio/mp4",
        "audio/aac",
        "audio/opus",
    )

internal fun isSupportedUploadMime(mime: String) = mime in SUPPORTED_UPLOAD_MIMES

private val responseJson = Json { ignoreUnknownKeys = true }

/**
 * Metadata returned by the upload server, used to build NIP-68 `imeta` tags.
 */
data class UploadResult(
    val url: String,
    val mimeType: String? = null,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    // Server-generated poster image (nostr.build returns one for videos). Emitted
    // as the imeta `thumb` field so clients can preview a video without fetching
    // it (the web player uses it as the <video> poster).
    val thumbnailUrl: String? = null,
) {
    /**
     * Build a NIP-68 `imeta` tag from this upload result.
     * Only includes fields that are present.
     */
    fun toImetaTag(): List<String> = buildList {
        add("imeta")
        add("url $url")
        mimeType?.let { add("m $it") }
        sha256?.let { add("x $it") }
        if (width != null && height != null) add("dim ${width}x$height")
        size?.let { add("size $it") }
        thumbnailUrl?.let { add("thumb $it") }
    }
}

object NostrBuildUploader {
    internal val client by lazy { createHttpClient() }

    /**
     * Upload a file to nostr.build with NIP-98 auth.
     * Returns [UploadResult] with URL and NIP-68 metadata (dimensions, hash, etc.).
     * [buildAuthHeader] receives (url, method) and must return "Nostr <base64>" or null.
     */
    suspend fun upload(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildAuthHeader: suspend (url: String, method: String) -> String?,
    ): Result<UploadResult> {
        val isCached = isBlobRef(filename)
        val displayName = if (isCached) filename.split("|").getOrNull(2) ?: filename else filename
        val sizeDesc =
            if (isCached) {
                "clipboard cache"
            } else {
                val kb = bytes.size / 1024
                "${bytes.size}B (${kb / 1024}.${((kb % 1024) * 100 / 1024).toString().padStart(2, '0')}MB)"
            }
        println("[Upload] START  file=$displayName  size=$sizeDesc  mime=$mimeType  url=$UPLOAD_URL")

        val authHeader = buildAuthHeader(UPLOAD_URL, "POST")
        if (authHeader == null) {
            println("[Upload] ERROR  auth header is null – not authenticated")
            return Result.Error(AppError.Auth.NotAuthenticated)
        }
        println("[Upload] AUTH   header obtained (length=${authHeader.length})")

        // Blob-ref uploads can't be retried — the JS cache entry is consumed on first use
        val maxAttempts = if (isCached) 1 else 3
        var lastException: Throwable? = null
        repeat(maxAttempts) { attempt ->
            println("[Upload] ATTEMPT ${attempt + 1}/$maxAttempts  file=$displayName")
            try {
                val result = doUpload(bytes, filename, mimeType, authHeader)
                println(
                    "[Upload] DONE   file=$displayName  result=${
                        when (result) {
                            is Result.Success -> "SUCCESS url=${result.data.url}"
                            is Result.Error -> "SERVER_ERROR ${result.error.message}"
                        }
                    }",
                )
                return result
            } catch (e: Throwable) {
                println("[Upload] EXCEPTION attempt=${attempt + 1}  type=${e::class.simpleName}  msg=${e.message}")
                e.cause?.let { println("[Upload]   cause: ${it::class.simpleName}: ${it.message}") }
                lastException = e
                if (attempt < maxAttempts - 1) {
                    println("[Upload] RETRY in 500ms…")
                    delay(500)
                }
            }
        }
        println("[Upload] FAILED after $maxAttempts attempts  file=$displayName  lastError=${lastException?.message}")
        return Result.Error(AppError.Unknown("Upload failed: ${lastException?.message}", lastException))
    }

    // Throws on IO/connection errors so the caller can retry.
    // Returns Result.Error for server-side errors (no retry needed).
    private suspend fun doUpload(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        authHeader: String,
    ): Result<UploadResult> {
        println("[Upload] SEND   submitting multipart to $UPLOAD_URL")
        val (statusCode, text) = executeUpload(UPLOAD_URL, bytes, filename, mimeType, authHeader)

        println("[Upload] RESPONSE  status=$statusCode")
        println("[Upload] BODY  (${text.length} chars): ${text.take(500)}${if (text.length > 500) "…" else ""}")

        if (statusCode !in 200..299) {
            val msg =
                runCatching {
                    responseJson
                        .parseToJsonElement(text)
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull() ?: "HTTP $statusCode"
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }

        val json = responseJson.parseToJsonElement(text).jsonObject

        val status = json["status"]?.jsonPrimitive?.contentOrNull
        if (status != "success") {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: text
            return Result.Error(AppError.Unknown("Upload failed: $msg"))
        }

        // nostr.build v2 returns data as array; handle object shape defensively
        val data = json["data"]
        val entry =
            when {
                data is JsonArray -> data.firstOrNull()?.jsonObject
                data is JsonObject -> data
                else -> null
            }
        val url =
            entry?.get("url")?.jsonPrimitive?.contentOrNull
                ?: return Result.Error(AppError.Unknown("Upload failed: no URL in response"))

        // Extract NIP-68 metadata from the response
        val dimensions = entry["dimensions"]?.jsonObject
        return Result.Success(
            UploadResult(
                url = url,
                mimeType =
                entry["mime"]?.jsonPrimitive?.contentOrNull
                    ?: entry["type"]?.jsonPrimitive?.contentOrNull,
                sha256 =
                entry["original_sha256"]?.jsonPrimitive?.contentOrNull
                    ?: entry["sha256"]?.jsonPrimitive?.contentOrNull,
                width = dimensions?.get("width")?.jsonPrimitive?.intOrNull,
                height = dimensions?.get("height")?.jsonPrimitive?.intOrNull,
                size = entry["size"]?.jsonPrimitive?.longOrNull,
                // nostr.build v2 returns a generated poster for video uploads.
                // Field name observed as "thumbnail"; fall back to "thumb".
                thumbnailUrl =
                entry["thumbnail"]?.jsonPrimitive?.contentOrNull
                    ?: entry["thumb"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    fun mimeTypeForFilename(filename: String): String {
        if (isBlobRef(filename)) return filename.split("|").getOrNull(1) ?: "application/octet-stream"
        return when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }
}
