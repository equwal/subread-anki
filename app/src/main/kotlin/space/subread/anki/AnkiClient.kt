package space.subread.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import space.subread.anki.core.Fields
import space.subread.anki.core.NoteType
import space.subread.anki.core.Source
import java.io.File

/**
 * AnkiDroid, through its content provider: the deck, the note type, the media files and the
 * notes. The authority, the paths and the columns are the FlashCardsContract of AnkiDroid. The
 * API library of AnkiDroid is not used: it comes from JitPack alone, and F-Droid builds from
 * Maven Central and source alone.
 */
class AnkiClient(private val context: Context) {

    /** A note of the collection: its id, its fields in order, and its tags. */
    class Note(val id: Long, val fields: Array<String>, val tags: Set<String>)

    private val resolver get() = context.contentResolver
    private val store = Store(context)

    /** The decks of the collection, by id. Empty when AnkiDroid does not answer. */
    fun decks(): Map<Long, String> = runCatching {
        resolver.query(DECKS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), c.getString(1))
            }
        }
    }.getOrNull().orEmpty()

    /** The note types of the collection, by id. */
    fun models(): Map<Long, String> = runCatching {
        resolver.query(MODELS, arrayOf("_id", "name"), null, null, null)?.use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), c.getString(1))
            }
        }
    }.getOrNull().orEmpty()

    /** The fields of a note type, in order. Empty when the note type is gone. */
    fun fields(modelId: Long): List<String> = runCatching {
        resolver.query(Uri.withAppendedPath(MODELS, modelId.toString()), arrayOf("field_names"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).split(SEPARATOR) else null
        }
    }.getOrNull().orEmpty()

    /** Makes a deck with this name. Returns its id, or null when AnkiDroid refused. */
    fun addDeck(name: String): Long? =
        resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) })?.lastPathSegment?.toLongOrNull()

    /** The deck the cards go to. The chosen one, else "SubRead", made when it is not there. */
    fun ensureDeck(): Long {
        val decks = decks()
        val chosen = store.deckId
        if (chosen != Store.NONE && decks.containsKey(chosen)) return chosen
        val id = decks.entries.firstOrNull { it.value == DECK_NAME }?.key
            ?: addDeck(DECK_NAME)
            ?: throw IllegalStateException("AnkiDroid did not make the deck")
        store.deckId = id
        return id
    }

    /** The note type of the cards. The chosen one, else the app's own, made when it is not there. */
    fun ensureModel(deckId: Long): Long {
        val models = models()
        val chosen = store.modelId
        if (chosen != Store.NONE && models.containsKey(chosen)) return chosen
        val id = models.entries.firstOrNull { it.value == NoteType.NAME }?.key
            ?: addModel(deckId)
            ?: throw IllegalStateException("AnkiDroid did not make the note type")
        store.modelId = id
        store.mapping = NoteType.MAPPING
        return id
    }

    /**
     * Makes the note type of the app: first the note type with its fields, then the template
     * of each card. Returns its id, or null when AnkiDroid refused.
     */
    private fun addModel(deckId: Long): Long? {
        val values = ContentValues().apply {
            put("name", NoteType.NAME)
            put("field_names", NoteType.FIELDS.joinToString(SEPARATOR))
            put("num_cards", NoteType.CARD_NAMES.size)
            put("css", NoteType.CSS)
            put("deck_id", deckId)
            put("sort_field_index", 0)
        }
        val model = resolver.insert(MODELS, values) ?: return null
        val templates = Uri.withAppendedPath(model, "templates")
        NoteType.CARD_NAMES.forEachIndexed { i, card ->
            val template = ContentValues().apply {
                put("card_template_name", card)
                put("question_format", NoteType.QUESTION_FORMATS[i])
                put("answer_format", NoteType.ANSWER_FORMATS[i])
            }
            resolver.update(Uri.withAppendedPath(templates, i.toString()), template, null, null)
        }
        return model.lastPathSegment?.toLongOrNull()
    }

    /** The note type of the cards when it exists already, or null. Makes nothing: for a look before the first card. */
    fun existingModel(): Long? {
        val models = models()
        val chosen = store.modelId
        if (chosen != Store.NONE && models.containsKey(chosen)) return chosen
        return models.entries.firstOrNull { it.value == NoteType.NAME }?.key
    }

    /** The name of the deck that the cards go to. */
    fun deckName(): String = decks()[store.deckId] ?: DECK_NAME

    /**
     * True when a note of the note type has [expression] in its first field, and that field
     * holds the word. False when the note type does not exist yet.
     */
    fun inAnki(expression: String): Boolean {
        val modelId = existingModel() ?: return false
        val fields = fields(modelId)
        if (fields.isEmpty() || mapping(modelId, fields)[fields.first()] != Source.EXPRESSION) return false
        return isDuplicate(modelId, expression)
    }

    /** What fills each field of [modelId]: the mapping the user set, else a guess from the names. */
    fun mapping(modelId: Long, fields: List<String>): Map<String, Source> {
        val set = store.mapping
        return if (set.isNotEmpty() && set.keys.containsAll(fields)) set else Fields.guess(fields)
    }

    /**
     * Copies a file into the media folder of AnkiDroid and returns its name there. [kind] is
     * `audio` or `image`; AnkiDroid takes the kind from the file. AnkiDroid reads the file
     * through [MediaProvider], so the read is granted to it for the copy.
     */
    @Suppress("UNUSED_PARAMETER")
    fun addMedia(file: File, kind: String): String? {
        val uri = Media.uriFor(context, file)
        val anki = packageName(context) ?: return null
        context.grantUriPermission(anki, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put("file_uri", uri.toString())
                put("preferred_name", file.nameWithoutExtension.replace(' ', '_'))
            }
            val stored = runCatching { resolver.insert(MEDIA, values) }.getOrNull() ?: return null
            // The path of the answer is the name of the file in the media folder, after a slash.
            return stored.path?.trimStart('/')?.takeIf { it.isNotEmpty() }
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** True when a note of [modelId] has [key] as its first field. */
    fun isDuplicate(modelId: Long, key: String): Boolean {
        if (key.isBlank()) return false
        return runCatching {
            val model = models()[modelId] ?: return@runCatching false
            val first = fields(modelId).firstOrNull() ?: return@runCatching false
            // The selection of the notes path is an Anki search. The quotes make one term of
            // "field:text", and the escapes stop Anki from reading _ and * as wildcards.
            val search = "\"${escape(first)}:${escape(key)}\" \"note:${escape(model)}\""
            resolver.query(NOTES, arrayOf("_id"), search, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /** Adds a note and puts its cards in the deck. Null when AnkiDroid refused it. */
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? {
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields.joinToString(SEPARATOR))
            put("tags", tags.joinToString(" ") { it.replace(' ', '_') })
        }
        val note = resolver.insert(NOTES, values) ?: return null
        val cards = Uri.withAppendedPath(note, "cards")
        resolver.query(cards, arrayOf("ord"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val deck = ContentValues().apply { put("deck_id", deckId) }
                resolver.update(Uri.withAppendedPath(cards, c.getString(0)), deck, null, null)
            }
        }
        return note.lastPathSegment?.toLongOrNull()
    }

    fun note(noteId: Long): Note? = runCatching {
        resolver.query(Uri.withAppendedPath(NOTES, noteId.toString()), arrayOf("_id", "flds", "tags"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                Note(
                    c.getLong(0),
                    c.getString(1).split(SEPARATOR).toTypedArray(),
                    c.getString(2).orEmpty().split(' ').filter { it.isNotBlank() }.toSet(),
                )
            }
        }
    }.getOrNull()

    fun updateFields(noteId: Long, fields: Array<String>): Boolean = runCatching {
        val values = ContentValues().apply { put("flds", fields.joinToString(SEPARATOR)) }
        resolver.update(Uri.withAppendedPath(NOTES, noteId.toString()), values, null, null) > 0
    }.getOrDefault(false)

    companion object {
        const val DECK_NAME = "SubRead"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val ROOT: Uri = Uri.parse("content://$AUTHORITY")
        private val NOTES: Uri = Uri.withAppendedPath(ROOT, "notes")
        private val MODELS: Uri = Uri.withAppendedPath(ROOT, "models")
        private val DECKS: Uri = Uri.withAppendedPath(ROOT, "decks")
        private val MEDIA: Uri = Uri.withAppendedPath(ROOT, "media")

        /** Anki keeps the fields of a note in one string, with this character between them. */
        private const val SEPARATOR = "\u001f"

        /** A text for an Anki search between quotes: \, ", _ and * get a backslash. */
        private fun escape(text: String): String =
            text.replace("\\", "\\\\").replace("\"", "\\\"").replace("_", "\\_").replace("*", "\\*")

        /** The package of AnkiDroid, or null when it is not installed. */
        fun packageName(context: Context): String? {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveContentProvider(AUTHORITY, 0)
            }
            return info?.packageName
        }

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
