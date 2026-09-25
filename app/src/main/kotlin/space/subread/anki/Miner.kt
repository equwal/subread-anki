package space.subread.anki

import android.content.Context
import android.graphics.Bitmap
import android.icu.text.BreakIterator
import android.net.Uri
import space.subread.anki.core.Fields
import space.subread.anki.core.MediaNames
import space.subread.anki.core.MineRequest
import space.subread.anki.core.Note
import space.subread.anki.core.PlayerReport
import space.subread.anki.core.Scans
import space.subread.anki.core.Sentences
import space.subread.anki.core.Source
import java.io.File

/**
 * Makes cards. [grab] keeps the media of the moment a text comes in: the picture of the
 * screen and the sound of the sentence. [add] puts one card in AnkiDroid with that media and
 * the term that the user chose in the pop-up.
 */
class Miner(private val context: Context) {

    private val store = Store(context)

    /** The media of one text, taken when it came in. One pop-up can add several cards with it. */
    class Grab(
        val request: MineRequest,
        val stem: String,
        val picture: File?,
        val sentenceAudio: File?,
        /** How long the sentence sound is, in milliseconds, or null without one. */
        val soundMs: Long?,
        /** Where the text is from: the sender, the player, or the app that sent it. */
        val source: String,
    ) {
        // The names in the media folder of AnkiDroid, once the first card copied the files.
        internal var ankiPicture: String? = null
        internal var ankiSound: String? = null
    }

    /** The parts of one card that the pop-up chose. */
    data class Card(
        val expression: String,
        val reading: String,
        val definition: String,
        val sentence: String,
        /** The word as it is in the sentence, for the bold. */
        val selection: String,
        val pitch: String,
        val frequency: String,
        /** Where the dictionary gives the audio of the word, or null. */
        val wordAudio: Uri?,
    )

    sealed class Result {
        class Added(val noteId: Long, val expression: String) : Result()
        class Duplicate(val expression: String) : Result()
        object Attached : Result()
        class Failed(val why: String) : Result()
    }

    /**
     * The media of the moment the text came in. [shot] is the screen from before the pop-up
     * drew. [line] is the overlay when the text is its subtitle line: then the sound is that
     * line, cut on the clock of the player. [grabbedAt] is `SystemClock.elapsedRealtimeNanos()`
     * of the moment. Slow: not on the UI thread.
     */
    fun grab(request: MineRequest, shot: Bitmap?, grabbedAt: Long, line: OverlayClient.Now?, source: String): Grab {
        Media.cleanOld(context)
        val stem = MediaNames.stem(request.expression ?: request.sentence ?: "card", System.currentTimeMillis())
        val picture = request.image?.let { Media.fetch(context, it, stem, Media.Kind.IMAGE) }
            ?: shot?.let { Media.writeJpeg(it, Media.file(context, "$stem.jpg")) }
        val (sound, ms) = sentenceAudio(request, line, stem, grabbedAt)
        return Grab(request, stem, picture, sound, ms, source)
    }

    /**
     * The sound of the sentence: cut from the file the request names, or fetched whole, or
     * taken from the capture. From the capture, the clip is the subtitle line, on the clock of
     * the player; without a line, the last seconds before the text came in.
     */
    private fun sentenceAudio(request: MineRequest, line: OverlayClient.Now?, stem: String, grabbedAt: Long): Pair<File?, Long?> {
        val audio = request.audio
        if (audio != null) {
            val start = request.audioStartMs
            val end = request.audioEndMs
            return if (start != null && end != null) {
                AudioCut.cut(context, audio, start, end, Media.file(context, "$stem.m4a")) to (end - start)
            } else {
                Media.fetch(context, audio, "${stem}_sentence", Media.Kind.AUDIO) to null
            }
        }
        val capture = CaptureService.instance ?: return null to null
        // A sender that gives the position of its player, and no overlay: the tap is the position.
        val report = line?.report ?: request.positionMs?.let { PlayerReport(it, grabbedAt, 1.0, false) }
        val cue = line?.line
        val cueStart = request.cueStartMs ?: cue?.let { it.startMs - it.offsetMs }
        val cueEnd = request.cueEndMs ?: cue?.let { it.endMs - it.offsetMs }
        val range = if (report != null && cueStart != null && cueEnd != null && cueEnd > cueStart) {
            report.wallRange(cueStart, cueEnd, store.padMs.toLong())
        } else {
            (grabbedAt - LAST_SECONDS * 1_000_000_000L)..grabbedAt
        }
        val clip = capture.clip(range.first, range.last, Media.file(context, "$stem.m4a"))
        return clip to clip?.let { (range.last - range.first) / 1_000_000L }
    }

    /** Adds one card to AnkiDroid. Not on the UI thread. */
    fun add(grab: Grab, card: Card): Result {
        val anki = AnkiClient(context)
        val deckId = anki.ensureDeck()
        val modelId = anki.ensureModel(deckId)
        val fields = anki.fields(modelId)
        if (fields.isEmpty()) return Result.Failed("the note type has no field")
        val mapping = anki.mapping(modelId, fields)
        if (store.skipDuplicates && card.expression.isNotEmpty() && mapping[fields.first()] == Source.EXPRESSION &&
            anki.isDuplicate(modelId, card.expression)
        ) {
            return Result.Duplicate(card.expression)
        }
        val sound = grab.ankiSound ?: grab.sentenceAudio?.let { anki.addMedia(it, "audio") }?.also { grab.ankiSound = it }
        val picture = grab.ankiPicture ?: grab.picture?.let { anki.addMedia(it, "image") }?.also { grab.ankiPicture = it }
        val stem = MediaNames.stem(card.expression.ifEmpty { "card" }, System.currentTimeMillis())
        val wordAudioFile = grab.request.wordAudio?.let { Media.fetch(context, it, "${stem}_word", Media.Kind.AUDIO) }
            ?: card.wordAudio?.let { ask -> DictionaryClient.audio(context, ask)?.let { Media.write(context, it, "${stem}_word", Media.Kind.AUDIO) } }
        val wordAudio = wordAudioFile?.let { anki.addMedia(it, "audio") }.orEmpty()
        val note = Note(
            expression = card.expression,
            reading = card.reading,
            definition = card.definition,
            sentence = card.sentence,
            sentenceAudioFile = sound.orEmpty(),
            wordAudioFile = wordAudio,
            imageFile = picture.orEmpty(),
            source = grab.source,
            pitch = card.pitch,
            frequency = card.frequency,
            selection = card.selection,
        )
        val values = Fields.render(note, fields, mapping)
        val tags = (store.tags.split(' ') + grab.request.tags).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val noteId = anki.addNote(modelId, deckId, values, tags) ?: return Result.Failed("AnkiDroid refused the note")
        store.lastNoteId = noteId
        store.lastNoteAt = System.currentTimeMillis()
        return Result.Added(noteId, card.expression)
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
        /** Without a subtitle line, the clip is this much sound before the text came in. */
        const val LAST_SECONDS = 8

        /** The longest selection that is a word. Longer, or with the end of a sentence in it, is a sentence. */
        const val MAX_WORD = 24

        /** True for a selection that is a sentence, not a word. */
        fun looksLikeSentence(text: String): Boolean =
            text.length > MAX_WORD || text.any { Sentences.isTerminator(it) } || text.trim().split(Regex("\\s+")).size > 3

        /**
         * The card for a term of the dictionary, or for the word alone when [entry] is null. The
         * word starts at [start] of [text] and is [length] characters long; -1 when the word is
         * not in the text. What the request gives wins over the dictionary.
         */
        fun card(request: MineRequest, text: String, start: Int, length: Int, entry: DictionaryClient.Entry?): Card {
            val inText = start >= 0 && length > 0 && start + length <= text.length
            val selection = if (inText) text.substring(start, start + length) else ""
            return Card(
                expression = entry?.expression ?: selection.ifEmpty { request.expression?.trim().orEmpty() },
                reading = request.reading ?: entry?.reading.orEmpty(),
                definition = request.definition ?: entry?.glossary.orEmpty(),
                sentence = if (inText) Scans.sentence(text, start, length) else request.sentence?.trim().orEmpty(),
                selection = selection,
                pitch = request.pitch ?: entry?.pitch.orEmpty(),
                frequency = request.frequency ?: entry?.frequency.orEmpty(),
                wordAudio = entry?.audio,
            )
        }

        /**
         * The word at [index] of [text], without a dictionary. A text that the user [selected]
         * as it is, and that is no sentence, is one word: the user chose its ends. Else the word
         * boundaries of ICU, which also split Japanese and Chinese.
         */
        fun wordAt(text: String, index: Int, selected: Boolean): IntRange {
            if (text.isEmpty()) return IntRange.EMPTY
            if (selected && !looksLikeSentence(text)) return text.indices
            val at = index.coerceIn(0, text.length - 1)
            val words = BreakIterator.getWordInstance()
            words.setText(text)
            val end = words.following(at).let { if (it == BreakIterator.DONE) text.length else it }
            val start = words.preceding(end).let { if (it == BreakIterator.DONE) 0 else it }
            return start until end
        }
    }
}
