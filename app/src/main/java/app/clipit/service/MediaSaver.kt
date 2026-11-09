package app.clipit.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.clipit.data.SavedFile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Publishes finished files to the shared "Download/Clip It" folder. */
object MediaSaver {
    const val FOLDER = "Clip It"
    private val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    suspend fun save(context: Context, source: File): SavedFile {
        val mime = mimeOf(source.name)
        val size = source.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            moveDirect(context, source, mime)?.let { (uri, name) ->
                return SavedFile(uri.toString(), name, mime, size)
            }
        }
        val uri = copyThroughMediaStore(context, source, mime)
        source.delete()
        return SavedFile(uri.toString(), source.name, mime, size)
    }

    /**
     * Android 11+ lets an app create files in Download/ by path. The work
     * folder is on the same volume, so this is a rename rather than a copy of
     * what may be a very large video. Returns null if the system refuses.
     */
    private suspend fun moveDirect(context: Context, source: File, mime: String): Pair<Uri, String>? {
        val target = try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val free = uniqueName(dir, source.name)
            if (!source.renameTo(free)) return null
            free
        } catch (e: SecurityException) {
            return null
        }
        val uri = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime)) { _, uri ->
                    if (continuation.isActive) continuation.resume(uri)
                }
            }
        }
        return (uri ?: Uri.fromFile(target)) to target.name
    }

    private fun uniqueName(dir: File, name: String): File {
        var candidate = File(dir, name)
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$stem (${n++})$ext")
        return candidate
    }

    private fun copyThroughMediaStore(context: Context, source: File, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create ${source.name} in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Removes a published file. Returns false if the system would not allow it. */
    fun delete(context: Context, file: SavedFile): Boolean = runCatching {
        val uri = Uri.parse(file.uri)
        if (uri.scheme == "file") File(uri.path!!).delete()
        else context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    fun exists(context: Context, file: SavedFile): Boolean = runCatching {
        val uri = Uri.parse(file.uri)
        if (uri.scheme == "file") File(uri.path!!).exists()
        else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
