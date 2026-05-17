package org.nostr.nostrord.network.upload

internal actual suspend fun executeUpload(
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String,
): Pair<Int, String> = ktorExecuteUpload(NostrBuildUploader.client, url, bytes, filename, mimeType, authHeader)
