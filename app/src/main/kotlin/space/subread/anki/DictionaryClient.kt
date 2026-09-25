package space.subread.anki

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri

/**
 * Asks SubRead Dictionary for the terms of a text, through its content provider
 * `content://space.subread.dictionary.lookup/lookup?text=...`. One row for each term of each
 * dictionary, the longest term first. The rows of one term and reading make one [Entry].
 */
object DictionaryClient {

    const val PACKAGE = "space.subread.dictionary"
    const val AUTHORITY = "space.subread.dictionary.lookup"

    /** One term: the entries of every dictionary for it, joined. */
    data class Entry(
        val expression: String,
        val reading: String,
        /** How many characters of the text the term covers. */
        val length: Int,
        /** HTML: the name of each dictionary and its glossary. */
        val glossary: String,
        val frequency: String,
        val pitch: String,
        /** Where the audio of the word is, or null. */
        val audio: Uri?,
    ) {
        val headword: String get() = if (reading.isEmpty() || reading == expression) expression else "$expression【$reading】"
    }

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess

    /** True when the installed dictionary has the provider: the versions before it do not. */
    fun answers(context: Context): Boolean =
        context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    /** The terms at the start of [text]. Empty when the dictionary is not there or has no term. */
    fun lookup(context: Context, text: String): List<Entry> {
        if (text.isEmpty() || !answers(context)) return emptyList()
        val uri = "content://$AUTHORITY/lookup".toUri().buildUpon().appendQueryParameter("text", text).build()
        val cursor = runCatching { context.contentResolver.query(uri, null, null, null, null) }.getOrNull() ?: return emptyList()
        data class Row(val expression: String, val reading: String, val length: Int, val dictionary: String, val glossary: String, val frequency: String, val pitch: String, val audio: String?)
        val rows = cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Row(
                        c.text("expression") ?: continue, c.text("reading").orEmpty(), c.long("length")?.toInt() ?: 0,
                        c.text("dictionary").orEmpty(), c.text("glossary").orEmpty(), c.text("frequency").orEmpty(),
                        c.text("pitch").orEmpty(), c.text("audio"),
                    ),
                )
            }
        }
        return rows.groupBy { it.expression to it.reading }.map { (key, group) ->
            val first = group.first()
            Entry(
                expression = key.first,
                reading = key.second,
                length = first.length,
                glossary = group.joinToString("<br>") { row ->
                    if (row.dictionary.isEmpty()) row.glossary else "<i>${escape(row.dictionary)}</i><br>${row.glossary}"
                },
                frequency = group.map { it.frequency }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                pitch = group.map { it.pitch }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                audio = first.audio?.toUri(),
            )
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun Cursor.text(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }
}
