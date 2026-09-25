package space.subread.anki

import android.content.Context
import android.os.SystemClock
import space.subread.anki.core.Fields
import space.subread.anki.core.MediaNames
import space.subread.anki.core.MineRequest
import space.subread.anki.core.Note
import space.subread.anki.core.PlayerReport
import space.subread.anki.core.Sentences
import space.subread.anki.core.Source
import java.io.File

/**
 * Makes a card from a request: fills what the request does not say from the overlay, the
 * dictionary and the capture, then adds it to AnkiDroid. Two steps, so that the user can be
 * shown the card in between: [prepare], then [add].
 */
class Miner(private val context: Context) {

    private val store = Store(context)

    /** A card ready to add. */
    class Plan(
        val request: MineRequest,
        /** The word as the user selected it, or null for a sentence card. */
        val selection: String?,
        val sentence: String?,
        /** The terms of the dictionary for the selection, the longest first. */
        val entries: List<DictionaryClient.Entry>,
        val sentenceAudio: File?,
        val image: File?,
        /** The stem of the media files of this card. */
        val stem: String,
    )

    sealed class Result {
        class Added(val noteId: Long, val expression: String) : Result()
        class Duplicate(val expression: String) : Result()
        object Attached : Result()
        class Failed(val why: String) : Result()
    }

    /** Gathers everything for the card. Slow: the sound is encoded, a URL is fetched. Not on the UI thread. */
    fun prepare(request: MineRequest): Plan {
        Media.cleanOld(context)
        var selection = request.expression?.trim()?.takeIf { it.isNotEmpty() }
        var sentence = request.sentence?.trim()?.takeIf { it.isNotEmpty() }
        // A whole sentence in the selection, and no sentence given: a sentence card.
        if (selection != null && sentence == null && looksLikeSentence(selection)) {
            sentence = selection
            selection = null
        }
        val overlay = OverlayClient.now(context)
        val line = overlay?.line
        if (sentence == null && line != null && (selection == null || line.text.contains(selection))) sentence = line.text
        val stamp = System.currentTimeMillis()
        val stem = MediaNames.stem(selection ?: sentence ?: "card", stamp)
        val entries = if (selection != null && request.definition == null) DictionaryClient.lookup(context, selection) else emptyList()
        val sentenceAudio = sentenceAudio(request, overlay, stem)
        val image = request.image?.let { Media.fetch(context, it, stem, Media.Kind.IMAGE) }
            ?: CaptureService.instance?.screenshot(Media.file(context, "$stem.jpg"))
        return Plan(request, selection, sentence, entries, sentenceAudio, image, stem)
    }

    /**
     * The sound of the sentence: cut from the file the request names, or fetched whole, or
     * taken from the capture. From the capture, the clip is the subtitle line of now, on the
     * clock of the player; without a line, the last seconds before the tap.
     */
    private fun sentenceAudio(request: MineRequest, overlay: OverlayClient.Now?, stem: String): File? {
        val audio = request.audio
        if (audio != null) {
            val start = request.audioStartMs
            val end = request.audioEndMs
            return if (start != null && end != null) AudioCut.cut(context, audio, start, end, Media.file(context, "$stem.m4a"))
            else Media.fetch(context, audio, "${stem}_sentence", Media.Kind.AUDIO)
        }
        val capture = CaptureService.instance ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        val line = overlay?.line
        // A sender that gives the position of its player, and no overlay: the tap is the position.
        val report = overlay?.report ?: request.positionMs?.let { PlayerReport(it, now, 1.0, false) }
        val cueStart = request.cueStartMs ?: line?.let { it.startMs - it.offsetMs }
        val cueEnd = request.cueEndMs ?: line?.let { it.endMs - it.offsetMs }
        val range = if (report != null && cueStart != null && cueEnd != null && cueEnd > cueStart) {
            report.wallRange(cueStart, cueEnd, store.padMs.toLong())
        } else {
            (now - LAST_SECONDS * 1_000_000_000L)..now
        }
        return capture.clip(range.first, range.last, Media.file(context, "$stem.m4a"))
    }

    /** Adds the card to AnkiDroid, with [entry] as the term. Not on the UI thread. */
    fun add(plan: Plan, entry: DictionaryClient.Entry?): Result {
        val anki = AnkiClient(context)
        val deckId = anki.ensureDeck()
        val modelId = anki.ensureModel(deckId)
        val fields = anki.fields(modelId)
        if (fields.isEmpty()) return Result.Failed("the note type has no field")
        val mapping = anki.mapping(modelId, fields)
        val request = plan.request
        val expression = entry?.expression ?: plan.selection ?: ""
        if (store.skipDuplicates && expression.isNotEmpty() && mapping[fields.first()] == Source.EXPRESSION && anki.isDuplicate(modelId, expression)) {
            return Result.Duplicate(expression)
        }
        val sentenceAudio = plan.sentenceAudio?.let { anki.addMedia(it, "audio") }.orEmpty()
        val image = plan.image?.let { anki.addMedia(it, "image") }.orEmpty()
        val wordAudio = (request.wordAudio ?: entry?.audio?.toString())
            ?.let { Media.fetch(context, it, "${plan.stem}_word", Media.Kind.AUDIO) }
            ?.let { anki.addMedia(it, "audio") }.orEmpty()
        val note = Note(
            expression = expression,
            reading = request.reading ?: entry?.reading.orEmpty(),
            definition = request.definition ?: entry?.glossary.orEmpty(),
            sentence = plan.sentence.orEmpty(),
            sentenceAudioFile = sentenceAudio,
            wordAudioFile = wordAudio,
            imageFile = image,
            source = request.source.orEmpty(),
            pitch = request.pitch ?: entry?.pitch.orEmpty(),
            frequency = request.frequency ?: entry?.frequency.orEmpty(),
            selection = plan.selection.orEmpty(),
        )
        val values = Fields.render(note, fields, mapping)
        val tags = (store.tags.split(' ') + request.tags).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val noteId = anki.addNote(modelId, deckId, values, tags) ?: return Result.Failed("AnkiDroid refused the note")
        store.lastNoteId = noteId
        store.lastNoteAt = System.currentTimeMillis()
        return Result.Added(noteId, expression)
    }

    /** A picture shared on its own goes onto the last card of the hour. Not on the UI thread. */
    fun attachPicture(source: String): Result {
        val noteId = store.lastNoteId
        if (noteId == Store.NONE || System.currentTimeMillis() - store.lastNoteAt > 3_600_000L) {
            return Result.Failed(context.getString(R.string.picture_no_card))
        }
        val anki = AnkiClient(context)
        val info = anki.note(noteId) ?: return Result.Failed(context.getString(R.string.picture_no_card))
        val modelId = anki.ensureModel(anki.ensureDeck())
        val fields = anki.fields(modelId)
        val mapping = anki.mapping(modelId, fields)
        val at = fields.indexOfFirst { mapping[it] == Source.IMAGE }
        if (at < 0) return Result.Failed("the note type has no picture field")
        val file = Media.fetch(context, source, MediaNames.stem("picture", System.currentTimeMillis()), Media.Kind.IMAGE)
            ?: return Result.Failed("the picture cannot be read")
        val name = anki.addMedia(file, "image") ?: return Result.Failed("AnkiDroid refused the picture")
        val values = info.fields.copyOf()
        values[at] = Fields.image(name)
        return if (anki.updateFields(noteId, values)) Result.Attached else Result.Failed("AnkiDroid refused the change")
    }

    companion object {
        /** Without a subtitle line, the clip is this much sound before the tap. */
        const val LAST_SECONDS = 8

        /** The longest selection that is a word. Longer, or with the end of a sentence in it, is a sentence. */
        const val MAX_WORD = 24

        /** True for a selection that is a sentence, not a word. */
        fun looksLikeSentence(text: String): Boolean =
            text.length > MAX_WORD || text.any { Sentences.isTerminator(it) } || text.trim().split(Regex("\\s+")).size > 3
    }
}
