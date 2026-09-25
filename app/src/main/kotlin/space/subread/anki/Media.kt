package space.subread.anki

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The media files of a card, in the cache, until AnkiDroid has copied them. */
object Media {

    /** A file bigger than this is refused: a card holds a clip, not a book. */
    const val MAX_BYTES = 30L * 1024 * 1024

    enum class Kind { AUDIO, IMAGE }

    fun dir(context: Context): File = File(context.cacheDir, "media").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** The Uri that AnkiDroid reads the file through. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Deletes the files of earlier cards. AnkiDroid copied them at once; a day is plenty. */
    fun cleanOld(context: Context) {
        val limit = System.currentTimeMillis() - 24 * 3600_000L
        dir(context).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    /**
     * Fetches a media file: a `content://` Uri, an `http(s)://` URL, or a `data:` Uri. The
     * bytes say what the file is; the name gets that extension. Null when the source cannot
     * be read, is empty, or is not a file of [kind].
     */
    fun fetch(context: Context, source: String, stem: String, kind: Kind): File? {
        val bytes = runCatching { bytes(context, source) }.getOrNull() ?: return null
        return write(context, bytes, stem, kind)
    }

    /** Writes the bytes of a file of [kind] to the cache, named by what the bytes are. Null for an unknown or empty file. */
    fun write(context: Context, bytes: ByteArray, stem: String, kind: Kind): File? {
        if (bytes.isEmpty()) return null
        val extension = extension(bytes, kind) ?: return null
        val out = file(context, "$stem.$extension")
        out.writeBytes(bytes)
        return out
    }

    /** The longest side of a picture on a card, in pixels. */
    const val MAX_SIDE = 1600

    /** The picture as a JPEG in [out], at most [MAX_SIDE] pixels on the long side. Null when the write fails. */
    fun writeJpeg(bitmap: Bitmap, out: File): File? {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest <= MAX_SIDE) bitmap else {
            val scale = MAX_SIDE.toFloat() / longest
            bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        }
        return runCatching {
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            out
        }.getOrNull()
    }

    /** The bytes of a source. */
    fun bytes(context: Context, source: String): ByteArray? {
        val uri = source.toUri()
        return when (uri.scheme?.lowercase()) {
            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes(MAX_BYTES) }
            "http", "https" -> {
                val connection = URL(source).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != 200) null else connection.inputStream.use { it.readBytes(MAX_BYTES) }
                } finally {
                    connection.disconnect()
                }
            }
            "data" -> {
                // data:audio/mpeg;base64,SUQz...
                val comma = source.indexOf(',')
                if (comma < 0) return null
                val head = source.substring(0, comma)
                val body = source.substring(comma + 1)
                if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            }
            // A file path would read the private files of this app for the sender.
            else -> null
        }
    }

    private fun java.io.InputStream.readBytes(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** The extension for the bytes of a file of [kind], from its first bytes. Null for an unknown file. */
    fun extension(bytes: ByteArray, kind: Kind): String? {
        fun at(offset: Int, text: String) =
            bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
        return when (kind) {
            Kind.IMAGE -> when {
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                at(1, "PNG") -> "png"
                at(0, "RIFF") && at(8, "WEBP") -> "webp"
                at(0, "GIF8") -> "gif"
                else -> null
            }
            Kind.AUDIO -> when {
                at(0, "ID3") -> "mp3"
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                at(0, "OggS") -> "ogg"
                at(0, "RIFF") && at(8, "WAVE") -> "wav"
                at(0, "fLaC") -> "flac"
                at(4, "ftyp") -> "m4a"
                else -> null
            }
        }
    }

    /** The MIME type for an extension of [extension]. AnkiDroid names its copy from it. */
    fun mimeType(extension: String): String? = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

/**
 * The file provider for AnkiDroid. It names the MIME type itself: AnkiDroid takes the
 * extension of its copy from the type, and the map of Android would call an `.m4a` an mp3.
 */
class MediaProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.let { Media.mimeType(it) } ?: super.getType(uri)
}
