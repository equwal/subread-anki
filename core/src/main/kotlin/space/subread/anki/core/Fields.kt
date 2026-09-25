package space.subread.anki.core

/** What the app knows about a card, with the media already in the Anki media folder. */
data class Note(
    val expression: String = "",
    val reading: String = "",
    /** HTML. */
    val definition: String = "",
    /** Plain text. */
    val sentence: String = "",
    /** The name of the sentence audio in the media folder of Anki, or empty. */
    val sentenceAudioFile: String = "",
    /** The name of the word audio in the media folder of Anki, or empty. */
    val wordAudioFile: String = "",
    /** The name of the picture in the media folder of Anki, or empty. */
    val imageFile: String = "",
    val source: String = "",
    val pitch: String = "",
    val frequency: String = "",
    /** The word as it is in the sentence, when it differs from [expression]: `食べた` for `食べる`. */
    val selection: String = "",
)

/** What a field of the note type is filled with. */
enum class Source {
    /** Nothing: the field stays empty. */
    NONE,
    EXPRESSION,
    READING,
    /** `漢字[かんじ]`: the expression with its reading, as Anki writes furigana. */
    FURIGANA,
    DEFINITION,
    /** The sentence, escaped. */
    SENTENCE,
    /** The sentence with the word in `<b>`, as the word is in the sentence. */
    SENTENCE_BOLD,
    /** `[sound:...]` of the sentence. */
    SENTENCE_AUDIO,
    /** `[sound:...]` of the word. */
    WORD_AUDIO,
    /** `<img src="...">`. */
    IMAGE,
    SOURCE,
    PITCH,
    FREQUENCY,
    /** An `x`: the note type shows a card layout when the field is not empty (Lapis). */
    MARK,
}

/** Fills the fields of a note type from a [Note]. */
object Fields {
    /** The value of one [Source] for a note. */
    fun value(note: Note, source: Source): String = when (source) {
        Source.NONE -> ""
        Source.EXPRESSION -> note.expression
        Source.READING -> note.reading
        Source.FURIGANA -> furigana(note.expression, note.reading)
        Source.DEFINITION -> note.definition
        Source.SENTENCE -> Sentences.escape(note.sentence)
        Source.SENTENCE_BOLD -> Sentences.emphasize(note.sentence, note.selection.ifEmpty { note.expression }.takeIf { it.isNotEmpty() })
        Source.SENTENCE_AUDIO -> sound(note.sentenceAudioFile)
        Source.WORD_AUDIO -> sound(note.wordAudioFile)
        Source.IMAGE -> image(note.imageFile)
        Source.SOURCE -> Sentences.escape(note.source)
        Source.PITCH -> note.pitch
        Source.FREQUENCY -> note.frequency
        Source.MARK -> "x"
    }

    /** One value for each of [fieldNames], from [mapping]. A field with no mapping is empty. */
    fun render(note: Note, fieldNames: List<String>, mapping: Map<String, Source>): Array<String> =
        Array(fieldNames.size) { i -> value(note, mapping[fieldNames[i]] ?: Source.NONE) }

    /** `[sound:name]`, or empty for no file. */
    fun sound(file: String): String = if (file.isEmpty()) "" else "[sound:$file]"

    /** `<img src="name">`, or empty for no file. */
    fun image(file: String): String = if (file.isEmpty()) "" else "<img src=\"${Sentences.escape(file)}\">"

    /**
     * The expression with its reading in the furigana form of Anki: ` 漢字[かんじ]`. The
     * expression alone when the reading is empty or the same as the expression.
     */
    fun furigana(expression: String, reading: String): String =
        if (reading.isEmpty() || reading == expression || expression.isEmpty()) expression
        else " $expression[$reading]"

    /**
     * A mapping for a note type from the names of its fields. Names are compared without
     * case and without anything but letters and digits, so `Sentence Audio`,
     * `sentence_audio` and `SentenceAudio` are the same field. Unknown fields get [Source.NONE].
     */
    fun guess(fieldNames: List<String>): Map<String, Source> {
        val out = LinkedHashMap<String, Source>()
        val taken = HashSet<Source>()
        for (name in fieldNames) {
            val source = BY_NAME[normalize(name)] ?: Source.NONE
            // The first field of a kind wins: two "Definition" fields do not both get it.
            out[name] = if (source != Source.NONE && source != Source.MARK && !taken.add(source)) Source.NONE else source
        }
        // A note type with no named expression field is a basic one: its first field gets
        // the expression, its second the definition.
        if (Source.EXPRESSION !in out.values && fieldNames.isNotEmpty() && out[fieldNames[0]] == Source.NONE) {
            out[fieldNames[0]] = Source.EXPRESSION
            if (Source.DEFINITION !in out.values && fieldNames.size > 1 && out[fieldNames[1]] == Source.NONE) {
                out[fieldNames[1]] = Source.DEFINITION
            }
        }
        return out
    }

    /** The name of a field with case, spaces and punctuation removed. */
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private val BY_NAME: Map<String, Source> = buildMap {
        fun put(source: Source, vararg names: String) = names.forEach { put(normalize(it), source) }
        put(Source.EXPRESSION, "Expression", "Word", "Term", "Vocab", "Vocabulary", "Target", "Kanji", "単語", "表現", "Front")
        put(Source.READING, "ExpressionReading", "Reading", "Yomi", "Kana", "Pronunciation", "読み", "Word Reading")
        put(Source.FURIGANA, "ExpressionFurigana", "Furigana", "VocabFurigana", "Word Furigana")
        put(Source.WORD_AUDIO, "ExpressionAudio", "WordAudio", "Audio", "VocabAudio", "Word Audio")
        put(
            Source.DEFINITION,
            "MainDefinition", "Definition", "Definitions", "Meaning", "Meanings", "Glossary", "Gloss", "Back", "意味",
        )
        put(Source.SENTENCE_BOLD, "Sentence", "Context", "Example", "ExampleSentence", "SentenceCloze", "Sentence Cloze", "文")
        put(Source.SENTENCE_AUDIO, "SentenceAudio", "ContextAudio", "AudioSentence", "ExampleAudio", "Sentence Audio")
        put(Source.IMAGE, "Picture", "Image", "Screenshot", "Img", "Photo")
        put(Source.SOURCE, "Source", "MiscInfo", "Document", "DocumentTitle", "Title", "Origin", "Notes", "Note")
        put(Source.PITCH, "PitchPosition", "Pitch", "PitchAccent", "Accent", "PitchCategories")
        put(Source.FREQUENCY, "Frequency", "FreqSort", "Freq", "Frequencies")
    }
}
