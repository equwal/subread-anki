package space.subread.anki

import android.content.Context
import space.subread.anki.core.Card
import space.subread.anki.core.Fields
import space.subread.anki.core.NoteType
import space.subread.anki.core.Slot
import java.io.File

/** A card with its files, before it goes to Anki. */
class Draft(
    var word: String = "",
    /** The word as it stands in the sentence: 食べた for 食べる. */
    var surface: String = "",
    var reading: String = "",
    var definition: String = "",
    /** A text with no word chosen: the user taps the word in it. */
    var text: String = "",
    var sentence: space.subread.anki.core.Sentence? = null,
    var image: File? = null,
    var audio: File? = null,
    var sentenceAudio: File? = null,
    var source: String = "",
    var url: String = "",
    var tags: String = "",
)

/** What happened to a card. */
data class Added(val noteId: Long, val word: String, val duplicates: Int)

/** Sends a draft to AnkiDroid: the deck and the note type of the settings, the files, then the note. */
class Notes(private val context: Context, private val store: Store, private val anki: AnkiDroid) {

    /** The deck of the settings, or the "SubRead" deck, made when Anki has none. */
    fun deck(): Pair<Long, String> {
        val decks = anki.decks()
        val chosen = store.deckId
        decks[chosen]?.let { return chosen to it }
        val id = decks.entries.firstOrNull { it.value == NoteType.NAME }?.key
            ?: anki.addDeck(NoteType.NAME)
            ?: throw IllegalStateException(context.getString(R.string.error_deck))
        store.deckId = id
        store.deckName = NoteType.NAME
        return id to NoteType.NAME
    }

    /** The note type of the settings, or the "SubRead" note type, made when Anki has none. */
    fun model(): AnkiDroid.Model {
        val models = anki.models()
        models.firstOrNull { it.id == store.modelId }?.let { return it }
        val existing = models.firstOrNull { it.name == NoteType.NAME }
        val model = existing ?: run {
            anki.addModel(NoteType.NAME, NoteType.FIELDS, NoteType.CARD, NoteType.FRONT, NoteType.BACK, NoteType.CSS)
            anki.models().firstOrNull { it.name == NoteType.NAME }
        } ?: throw IllegalStateException(context.getString(R.string.error_model))
        store.modelId = model.id
        store.modelName = model.name
        store.mapping = if (model.fields == NoteType.FIELDS) NoteType.MAPPING else Fields.guessAll(model.fields)
        return model
    }

    /** The mapping of the settings for this note type; a guess from the field names when the settings have none for it. */
    fun mapping(model: AnkiDroid.Model): Map<String, Slot> {
        val stored = store.mapping
        return if (model.fields.any { it in stored }) stored else Fields.guessAll(model.fields)
    }

    /** Puts the files in the media folder of Anki and adds the note. Throws with a message when a step fails. */
    fun add(draft: Draft): Added {
        val (deckId, _) = deck()
        val model = model()
        val mapping = mapping(model)
        val card = Card(
            word = draft.word,
            reading = draft.reading,
            definition = draft.definition,
            sentence = draft.sentence,
            image = media(draft.image, "subread-image"),
            audio = media(draft.audio, "subread-word"),
            sentenceAudio = media(draft.sentenceAudio, "subread-sentence"),
            source = draft.source,
            url = draft.url,
        )
        val fields = Fields.fill(card, model.fields, mapping)
        if (fields.all { it.isEmpty() }) throw IllegalStateException(context.getString(R.string.error_empty))
        val duplicates = anki.duplicates(model, fields[0])
        val tags = (store.tags + " " + draft.tags).split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        val noteId = anki.addNote(model.id, deckId, fields, tags) ?: throw IllegalStateException(context.getString(R.string.error_note))
        return Added(noteId, draft.word, duplicates)
    }

    private fun media(file: File?, stem: String): String {
        if (file == null || !file.isFile) return ""
        return anki.addMedia(Media.uri(context, file), stem) ?: throw IllegalStateException(context.getString(R.string.error_media, file.name))
    }
}
