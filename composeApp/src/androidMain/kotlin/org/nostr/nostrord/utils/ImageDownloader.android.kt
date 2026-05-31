package org.nostr.nostrord.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val supportsImageDownload: Boolean = true

@Composable
actual fun rememberImageDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean {
    val context = LocalContext.current.applicationContext
    return { bytes, fileName, mimeType ->
        val ok = withContext(Dispatchers.IO) { saveImage(context, bytes, fileName, mimeType) }
        // Toast (not a snackbar): the fullscreen image Dialog is a separate window, so a
        // snackbar in the host window would render behind it and never be seen.
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (ok) "Image saved to Pictures" else "Couldn't save image",
                Toast.LENGTH_SHORT,
            ).show()
        }
        ok
    }
}

/**
 * On API 29+ inserts into MediaStore Pictures/Nostrord (no permission needed under scoped
 * storage). On older versions falls back to the app-specific external Pictures dir, which is
 * also permission-free but not visible in the gallery.
 */
private fun saveImage(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mimeType: String,
): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nostrord")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).outputStream().use { it.write(bytes) }
            true
        }
    } catch (_: Exception) {
        false
    }
}
