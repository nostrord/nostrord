package org.nostr.nostrord.network.upload

import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Promise

// Decode via the browser's createImageBitmap, which reports the intrinsic size for every
// raster format the platform can paint. Wrapped in try/catch so an undecodable blob (e.g. SVG)
// just yields null and the upload omits `dim`.
actual suspend fun decodeImageDimensions(bytes: ByteArray, mimeType: String?): Pair<Int, Int>? {
    if (bytes.isEmpty()) return null
    return try {
        val arr = Int8Array(bytes.size)
        for (i in bytes.indices) arr[i] = bytes[i]
        val blob = Blob(arrayOf<dynamic>(arr), BlobPropertyBag(type = mimeType ?: ""))
        val bitmap: dynamic = createImageBitmapAsync(blob).await()
        val w = (bitmap.width as Int)
        val h = (bitmap.height as Int)
        bitmap.close()
        if (w > 0 && h > 0) w to h else null
    } catch (e: Throwable) {
        null
    }
}

// self.createImageBitmap exists in both window and worker scopes.
private fun createImageBitmapAsync(blob: Blob): Promise<dynamic> = js("self.createImageBitmap(blob)")
