package space.subread.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.NoteInfo
import space.subread.anki.core.Fields
import space.subread.anki.core.NoteType
import space.subread.anki.core.Source
import java.io.File

/** The AnkiDroid API: the deck, the note type, the media files and the notes. */
class AnkiClient(private val context: Context) {

    private val api = AddContentApi(context)
    private val store = Store(context)

    /** The decks of the collection, by id. Empty when AnkiDroid does not answer. */
    fun decks(): Map<Long, String> = runCatching { api.deckList }.getOrNull().orEmpty()

    /** The note types of the collection, by id. */
    fun models(): Map<Long, String> = runCatching { api.modelList }.getOrNull().orEmpty()

    /** The fields of a note type, in order. Empty when the note type is gone. */
    fun fields(modelId: Long): List<String> = runCatching { api.getFieldList(modelId) }.getOrNull()?.toList().orEmpty()

    /** The deck the cards go to. The chosen one, else "SubRead", made when it is not there. */
    fun ensureDeck(): Long {
        val decks = decks()
        val chosen = store.deckId
        if (chosen != Store.NONE && decks.containsKey(chosen)) return chosen
        val id = decks.entries.firstOrNull { it.value == DECK_NAME }?.key
            ?: api.addNewDeck(DECK_NAME)
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
            ?: api.addNewCustomModel(
                NoteType.NAME,
                NoteType.FIELDS.toTypedArray(),
                NoteType.CARD_NAMES.toTypedArray(),
                NoteType.QUESTION_FORMATS.toTypedArray(),
                NoteType.ANSWER_FORMATS.toTypedArray(),
                NoteType.CSS,
                deckId,
                0,
            )
            ?: throw IllegalStateException("AnkiDroid did not make the note type")
        store.modelId = id
        store.mapping = NoteType.MAPPING
        return id
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
     * `audio` or `image`. AnkiDroid reads the file through [MediaProvider], so the read is
     * granted to it for the copy.
     */
    fun addMedia(file: File, kind: String): String? {
        val uri = Media.uriFor(context, file)
        val anki = packageName(context) ?: return null
        context.grantUriPermission(anki, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val stem = file.nameWithoutExtension
            val formatted = api.addMediaFromUri(uri, stem, kind) ?: return null
            // The API answers with the field text, `[sound:name]` or `<img src="name" />`: take the name.
            return Regex("\\[sound:(.+)]|src=\"([^\"]+)\"").find(formatted)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** True when a note of [modelId] has [key] as its first field. */
    fun isDuplicate(modelId: Long, key: String): Boolean =
        key.isNotEmpty() && runCatching { api.findDuplicateNotes(modelId, key) }.getOrNull().orEmpty().isNotEmpty()

    /** Adds a note. Null when AnkiDroid refused it. */
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? =
        api.addNote(modelId, deckId, fields, tags)

    fun note(noteId: Long): NoteInfo? = runCatching { api.getNote(noteId) }.getOrNull()

    fun updateFields(noteId: Long, fields: Array<String>): Boolean = runCatching { api.updateNoteFields(noteId, fields) }.getOrDefault(false)

    companion object {
        const val DECK_NAME = "SubRead"
        const val PERMISSION = AddContentApi.READ_WRITE_PERMISSION

        /** The package of AnkiDroid, or null when it is not installed. */
        fun packageName(context: Context): String? = AddContentApi.getAnkiDroidPackageName(context)

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
