package org.nostr.nostrord.network.upload

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

internal expect suspend fun executeUpload(
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String
): Pair<Int, String>

internal suspend fun ktorExecuteUpload(
    client: HttpClient,
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String
): Pair<Int, String> {
    val response = client.submitFormWithBinaryData(
        url = url,
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
        timeout {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis  = 60_000
        }
    }
    return Pair(response.status.value, response.bodyAsText())
}
