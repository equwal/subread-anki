package space.subread.anki

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri

/**
 * Asks SubRead Dictionary for the terms of a text, through its content provider
 * `content://space.subread.dictionary.lookup` (see docs/provider-api.md of that app).
 *
 * `terms?text=…` gives one row for each term of each dictionary, the longest term first. The
 * rows of one term and reading make one [Entry]. `audio?expression=…&reading=…` gives one row
 * with the file of the first source that has the audio, and `audio/<file>` its bytes.
 */
object DictionaryClient {

    const val PACKAGE = "space.subread.dictionary"

    /** The release authority, then the one of a debug build, which installs next to the release. */
    val AUTHORITIES = listOf("space.subread.dictionary.lookup", "space.subread.dictionary.debug.lookup")

    /** The authority of the installed dictionary that answers, or null. */
    fun authority(context: Context): String? =
        AUTHORITIES.firstOrNull { context.packageManager.resolveContentProvider(it, 0) != null }

    /** One term: the entries of every dictionary for it, joined. */
    data class Entry(
        val expression: String,
        val reading: String,
        /** How many characters of the text the term covers. */
        val length: Int,
        /** The deinflection from the text to the term, for example `past`. Empty for the dictionary form. */
        val reasons: String,
        /** HTML: the name of each dictionary and its glossary. */
        val glossary: String,
        val frequency: String,
        val pitch: String,
        /** Where the audio of the word is asked for, or null. */
        val audio: Uri?,
    ) {
        val headword: String get() = if (reading.isEmpty() || reading == expression) expression else "$expression【$reading】"
    }

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess || authority(context) != null

    /** True when the installed dictionary has the provider: the versions before it do not. */
    fun answers(context: Context): Boolean = authority(context) != null

    /**
     * The terms that start at [offset] in [text], the longest first. Empty when the dictionary
     * is not there or has no term there.
     */
    fun lookup(context: Context, text: String, offset: Int = 0): List<Entry> {
        val authority = authority(context)
        if (text.isEmpty() || offset !in text.indices || authority == null) return emptyList()
        val uri = "content://$authority/terms".toUri().buildUpon()
            .appendQueryParameter("text", text).appendQueryParameter("offset", offset.toString()).build()
        val cursor = runCatching { context.contentResolver.query(uri, null, null, null, null) }.getOrNull() ?: return emptyList()
        data class Row(
            val expression: String, val reading: String, val length: Int, val reasons: String,
            val dictionary: String, val glossary: String, val frequency: String, val pitch: String,
        )
        val rows = cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Row(
                        c.text("expression") ?: continue, c.text("reading").orEmpty(), c.long("length")?.toInt() ?: 0,
                        c.text("reasons").orEmpty(), c.text("dictionary").orEmpty(),
                        c.text("definition_html") ?: c.text("glossary").orEmpty(),
                        c.text("frequency").orEmpty(), c.text("pitch").orEmpty(),
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
                reasons = first.reasons,
                glossary = group.joinToString("<br>") { row ->
                    if (row.dictionary.isEmpty()) row.glossary else "<i>${escape(row.dictionary)}</i><br>${row.glossary}"
                },
                frequency = group.map { it.frequency }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                pitch = group.map { it.pitch }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                audio = "content://$authority/audio".toUri().buildUpon()
                    .appendQueryParameter("expression", key.first).appendQueryParameter("reading", key.second).build(),
            )
        }
    }

    /**
     * The bytes of the audio of a term, or null when no source has it. The provider names the
     * file in a row first, then gives the bytes at `audio/<file>`.
     */
    fun audio(context: Context, ask: Uri): ByteArray? = runCatching {
        val name = context.contentResolver.query(ask, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.text("file") else null
        } ?: return null
        val file = "content://${ask.authority}/audio/$name".toUri()
        context.contentResolver.openInputStream(file)?.use { it.readBytes() }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

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
