package space.subread.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

/**
 * Talks to AnkiDroid through its content provider. The contract is the FlashCardsContract of
 * AnkiDroid: the authority, the paths and the columns below. The API library of AnkiDroid is not
 * used: it comes from JitPack alone, and F-Droid builds from Maven Central and source alone.
 *
 * Each call can throw: AnkiDroid is not installed, the permission is not given, or AnkiDroid
 * has no collection open. The caller shows the message.
 */
class AnkiDroid(private val context: Context) {

    /** A note type: its id, its name, and its fields in order. */
    data class Model(val id: Long, val name: String, val fields: List<String>)

    private val resolver get() = context.contentResolver

    /** The package of AnkiDroid, or null when no AnkiDroid with the provider is installed. */
    val packageName: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.ComponentInfoFlags.of(0))?.packageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
        }

    val installed: Boolean get() = packageName != null

    val permitted: Boolean get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun decks(): Map<Long, String> = resolver.query(DECKS, null, null, null, null)?.use { c ->
        buildMap {
            while (c.moveToNext()) put(c.getLong(c.getColumnIndexOrThrow("deck_id")), c.getString(c.getColumnIndexOrThrow("deck_name")))
        }
    } ?: emptyMap()

    fun addDeck(name: String): Long? =
        resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) })?.lastPathSegment?.toLongOrNull()

    fun models(): List<Model> = resolver.query(MODELS, null, null, null, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Model(
                        c.getLong(c.getColumnIndexOrThrow("_id")),
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getString(c.getColumnIndexOrThrow("field_names")).split(SEPARATOR),
                    ),
                )
            }
        }
    } ?: emptyList()

    fun model(id: Long): Model? = models().firstOrNull { it.id == id }

    /** Makes a note type with one card. Returns its id. */
    fun addModel(name: String, fields: List<String>, cardName: String, front: String, back: String, css: String): Long? {
        val values = ContentValues().apply {
            put("name", name)
            put("field_names", fields.joinToString(SEPARATOR))
            put("num_cards", 1)
            put("css", css)
        }
        val model = resolver.insert(MODELS, values) ?: return null
        val template = Uri.withAppendedPath(Uri.withAppendedPath(model, "templates"), "0")
        val templateValues = ContentValues().apply {
            put("card_template_name", cardName)
            put("question_format", front)
            put("answer_format", back)
        }
        resolver.update(template, templateValues, null, null)
        return model.lastPathSegment?.toLongOrNull()
    }

    /**
     * Copies a file into the media folder of Anki. AnkiDroid reads the file through the URI, so
     * it gets the read permission first. Returns the name that a field refers to, or null.
     */
    fun addMedia(file: Uri, preferredName: String): String? {
        val anki = packageName ?: return null
        context.grantUriPermission(anki, file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put("file_uri", file.toString())
                put("preferred_name", preferredName.replace(' ', '_'))
            }
            val stored = resolver.insert(MEDIA, values) ?: return null
            // The path of the answer is the file name with a slash in front.
            return stored.path?.trimStart('/')?.takeIf { it.isNotEmpty() }
        } finally {
            context.revokeUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Adds a note and puts its cards in the deck. Returns the id of the note, or null. */
    fun addNote(modelId: Long, deckId: Long, fields: List<String>, tags: Collection<String>): Long? {
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields.joinToString(SEPARATOR))
            put("tags", tags.joinToString(" "))
        }
        val note = resolver.insert(NOTES, values) ?: return null
        val cards = Uri.withAppendedPath(note, "cards")
        resolver.query(cards, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ord = c.getString(c.getColumnIndexOrThrow("ord"))
                resolver.update(Uri.withAppendedPath(cards, ord), ContentValues().apply { put("deck_id", deckId) }, null, null)
            }
        }
        return note.lastPathSegment?.toLongOrNull()
    }

    /** How many notes of the note type have this first field. Zero when the search fails. */
    fun duplicates(model: Model, first: String): Int = runCatching {
        val key = first.replace("\"", "").replace("*", "").replace("_", "")
        if (key.isBlank()) return 0
        val search = "${model.fields[0]}:\"$key\" note:\"${model.name}\""
        resolver.query(NOTES, arrayOf("_id"), search, null, null)?.use { it.count } ?: 0
    }.getOrDefault(0)

    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        const val PACKAGE = "com.ichi2.anki"
        const val INSTALL = "https://github.com/ankidroid/Anki-Android/releases/latest"
        private val ROOT = "content://$AUTHORITY".toUri()
        private val NOTES: Uri = Uri.withAppendedPath(ROOT, "notes")
        private val MODELS: Uri = Uri.withAppendedPath(ROOT, "models")
        private val DECKS: Uri = Uri.withAppendedPath(ROOT, "decks")
        private val MEDIA: Uri = Uri.withAppendedPath(ROOT, "media")

        /** Anki keeps the fields of a note in one string, with this character between them. */
        private const val SEPARATOR = "\u001f"
    }
}
