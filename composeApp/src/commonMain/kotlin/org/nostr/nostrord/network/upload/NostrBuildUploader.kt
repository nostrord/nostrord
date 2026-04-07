package org.nostr.nostrord.network.upload

import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result

private const val UPLOAD_URL = "https://nostr.build/api/v2/upload/files"

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
    val size: Long? = null
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
        if (width != null && height != null) add("dim ${width}x${height}")
        size?.let { add("size $it") }
    }
}

object NostrBuildUploader {
    private val client by lazy { createHttpClient() }

    /**
     * Upload a file to nostr.build with NIP-98 auth.
     * Returns [UploadResult] with URL and NIP-68 metadata (dimensions, hash, etc.).
     * [buildAuthHeader] receives (url, method) and must return "Nostr <base64>" or null.
     */
    suspend fun upload(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        buildAuthHeader: suspend (url: String, method: String) -> String?
    ): Result<UploadResult> {
        return try {
            val authHeader = buildAuthHeader(UPLOAD_URL, "POST")
                ?: return Result.Error(AppError.Auth.NotAuthenticated)

            val response = client.submitFormWithBinaryData(
                url = UPLOAD_URL,
                formData = formData {
                    append(
                        key = "fileToUpload",
                        value = bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentDisposition,
                                "form-data; name=\"fileToUpload\"; filename=\"$filename\"")
                            append(HttpHeaders.ContentType, mimeType)
                        }
                    )
                }
            ) {
                headers.append(HttpHeaders.Authorization, authHeader)
            }

            val text = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val msg = runCatching {
                    responseJson.parseToJsonElement(text).jsonObject["message"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "HTTP ${response.status.value}"
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
            val entry = when {
                data is JsonArray -> data.firstOrNull()?.jsonObject
                data is JsonObject -> data
                else -> null
            }
            val url = entry?.get("url")?.jsonPrimitive?.contentOrNull
                ?: return Result.Error(AppError.Unknown("Upload failed: no URL in response"))

            // Extract NIP-68 metadata from the response
            val dimensions = entry["dimensions"]?.jsonObject
            val result = UploadResult(
                url = url,
                mimeType = entry["mime"]?.jsonPrimitive?.contentOrNull
                    ?: entry["type"]?.jsonPrimitive?.contentOrNull,
                sha256 = entry["original_sha256"]?.jsonPrimitive?.contentOrNull
                    ?: entry["sha256"]?.jsonPrimitive?.contentOrNull,
                width = dimensions?.get("width")?.jsonPrimitive?.intOrNull,
                height = dimensions?.get("height")?.jsonPrimitive?.intOrNull,
                size = entry["size"]?.jsonPrimitive?.longOrNull
            )

            Result.Success(result)
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown("Upload failed: ${e.message}", e))
        }
    }

    fun mimeTypeForFilename(filename: String): String =
        when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            "mov"         -> "video/quicktime"
            "mp3"         -> "audio/mpeg"
            "ogg"         -> "audio/ogg"
            "wav"         -> "audio/wav"
            "flac"        -> "audio/flac"
            "m4a"         -> "audio/mp4"
            "aac"         -> "audio/aac"
            else          -> "application/octet-stream"
        }
}
