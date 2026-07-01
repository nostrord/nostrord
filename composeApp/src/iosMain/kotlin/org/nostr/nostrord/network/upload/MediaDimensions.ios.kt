package org.nostr.nostrord.network.upload

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

// UIImage(data:) reads the header to expose `size` (in points). dataWithBytes:length: copies the
// buffer, so the pinned region is only needed for the create call.
@OptIn(ExperimentalForeignApi::class)
actual suspend fun decodeImageDimensions(bytes: ByteArray, mimeType: String?): Pair<Int, Int>? {
    if (bytes.isEmpty()) return null
    return try {
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val image = UIImage.imageWithData(data) ?: return null
        val (w, h) = image.size.useContents { width.toInt() to height.toInt() }
        if (w > 0 && h > 0) w to h else null
    } catch (e: Throwable) {
        null
    }
}
