package space.subread.anki

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import java.io.File

/**
 * Asks SubRead Dictionary for the definition and the audio of a word. The dictionary app has a
 * content provider for this: `terms?text=` gives the terms at the start of a text, `audio?...`
 * gives the first audio file that a source has.
 */
class Dictionary(private val context: Context) {

    /** One term of one dictionary. [length] is how many characters of the text the term covers. */
    data class Entry(val expression: String, val reading: String, val length: Int, val dictionary: String, val definition: String)

    private val resolver get() = context.contentResolver

    /** The authority of the installed dictionary app: the release one, else the debug one. Null without the app. */
    private val authority: String?
        get() = AUTHORITIES.firstOrNull { authority ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0)) != null
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveContentProvider(authority, 0) != null
            }
        }

    val installed: Boolean get() = authority != null

    private fun root(): Uri? = authority?.let { "content://$it".toUri() }

    /** The terms at the start of the text, the longest first. Empty when nothing matches or the dictionary is away. */
    fun lookup(text: String): List<Entry> = runCatching {
        val terms = Uri.withAppendedPath(root() ?: return emptyList(), "terms")
        val uri = terms.buildUpon().appendQueryParameter("text", text).build()
        resolver.query(uri, null, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Entry(
                            c.getString(c.getColumnIndexOrThrow("expression")),
                            c.getString(c.getColumnIndexOrThrow("reading")),
                            c.getInt(c.getColumnIndexOrThrow("length")),
                            c.getString(c.getColumnIndexOrThrow("dictionary")),
                            c.getString(c.getColumnIndexOrThrow("definition_html")),
                        ),
                    )
                }
            }
        }
    }.getOrNull().orEmpty()

    /** The audio of a term, copied into the cache. Null when no source has it. */
    fun audio(expression: String, reading: String): File? = runCatching {
        val audio = Uri.withAppendedPath(root() ?: return null, "audio")
        val uri = audio.buildUpon().appendQueryParameter("expression", expression).appendQueryParameter("reading", reading).build()
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("file")) else null
        } ?: return null
        val bytes = resolver.openInputStream(Uri.withAppendedPath(audio, name))?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) null else Media.write(context, bytes, "word", "audio")
    }.getOrNull()

    companion object {
        const val PACKAGE = "space.subread.dictionary"
        const val INSTALL = "https://github.com/equwal/subread-dictionary/releases/latest"

        /** The provider of the dictionary app, and the one of its debug build. */
        val AUTHORITIES = listOf("space.subread.dictionary.lookup", "space.subread.dictionary.debug.lookup")

        /**
         * The definition of the first term: the glossary of each dictionary that has that term
         * with that reading, each under the name of its dictionary.
         */
        fun definition(entries: List<Entry>): String {
            val first = entries.firstOrNull() ?: return ""
            return entries.filter { it.expression == first.expression && it.reading == first.reading }
                .joinToString("<br><br>") { "<i>${it.dictionary}</i><br>${it.definition}" }
        }
    }
}
