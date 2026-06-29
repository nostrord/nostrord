package org.nostr.nostrord.network.upload

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Bounds-only decode (inJustDecodeBounds): reads the header for width/height without
// allocating the full bitmap, so probing a 20 MB image is cheap.
actual suspend fun decodeImageDimensions(bytes: ByteArray, mimeType: String?): Pair<Int, Int>? {
    if (bytes.isEmpty()) return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }.getOrNull()
    }
}
