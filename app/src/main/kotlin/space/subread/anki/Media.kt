package space.subread.anki

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** The files of a card on their way to AnkiDroid: in the cache, shared through the file provider. */
object Media {

    /** Files older than this go. A card takes seconds; an hour is safe. */
    private const val KEEP_MS = 60 * 60_000L

    fun dir(context: Context): File = File(context.cacheDir, "anki").apply { mkdirs() }

    /** A fresh file name in the cache, with the extension. */
    fun file(context: Context, stem: String, extension: String): File =
        File(dir(context), "$stem-${System.nanoTime()}.$extension")

    /** The URI that another app, AnkiDroid, reads the file through. */
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Copies what a URI gives into the cache. Null when the URI cannot be read. */
    fun copy(context: Context, source: Uri, stem: String, kind: String): File? = runCatching {
        val bytes = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        if (bytes.isEmpty()) return null
        val extension = extension(context, source) ?: sniff(bytes, kind)
        file(context, stem, extension).also { it.writeBytes(bytes) }
    }.getOrNull()

    /** Writes bytes to the cache, with the extension that their format has. */
    fun write(context: Context, bytes: ByteArray, stem: String, kind: String): File =
        file(context, stem, sniff(bytes, kind)).also { it.writeBytes(bytes) }

    /** The extension of the type that the provider of the URI reports, or of the name of the URI. */
    private fun extension(context: Context, uri: Uri): String? {
        val fromType = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (fromType != null) return fromType
        val name = uri.lastPathSegment ?: return null
        return name.substringAfterLast('.', "").takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }?.lowercase()
    }

    /** The extension from the first bytes of a file. [kind] is "audio" or "image": the extension when the bytes say nothing. */
    fun sniff(bytes: ByteArray, kind: String): String {
        fun startsWith(prefix: String, at: Int = 0) = bytes.size >= at + prefix.length &&
            prefix.indices.all { bytes[at + it] == prefix[it].code.toByte() }
        return when {
            startsWith("ID3") || (bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            startsWith("OggS") -> "ogg"
            startsWith("RIFF") && startsWith("WAVE", 8) -> "wav"
            startsWith("fLaC") -> "flac"
            startsWith("ftyp", 4) -> "m4a"
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            startsWith("\u0089PNG") -> "png"
            startsWith("GIF8") -> "gif"
            startsWith("RIFF") && startsWith("WEBP", 8) -> "webp"
            kind == "image" -> "jpg"
            else -> "mp3"
        }
    }

    /** Deletes the old files of the cache. */
    fun clean(context: Context) {
        val limit = System.currentTimeMillis() - KEEP_MS
        dir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
