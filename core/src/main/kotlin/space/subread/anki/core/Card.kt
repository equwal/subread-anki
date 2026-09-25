package space.subread.anki.core

/**
 * What goes on a card. The media are the names of the files in the media folder of Anki,
 * empty when the card has none. The definition is HTML.
 */
data class Card(
    val word: String,
    val reading: String = "",
    val definition: String = "",
    val sentence: Sentence? = null,
    val image: String = "",
    val audio: String = "",
    val sentenceAudio: String = "",
    val source: String = "",
    val url: String = "",
)

/** What a field of a note type can hold. The user maps each field to one slot. */
enum class Slot(val label: String) {
    NONE("Nothing"),
    WORD("Word"),
    READING("Reading"),
    FURIGANA("Word with furigana"),
    DEFINITION("Definition"),
    DEFINITION_TEXT("Definition as plain text"),
    SENTENCE("Sentence"),
    SENTENCE_BOLD("Sentence, the word in bold"),
    SENTENCE_CLOZE("Sentence, the word as a cloze"),
    IMAGE("Image"),
    AUDIO("Word audio"),
    SENTENCE_AUDIO("Sentence audio"),
    SOURCE("Source"),
    URL("Link"),
}

object Fields {

    /** The value of a field that holds [slot], as Anki stores it: HTML. */
    fun render(card: Card, slot: Slot): String = when (slot) {
        Slot.NONE -> ""
        Slot.WORD -> Html.escape(card.word)
        Slot.READING -> Html.escape(card.reading)
        Slot.FURIGANA -> Html.escape(Furigana.bracket(card.word, card.reading))
        Slot.DEFINITION -> card.definition
        Slot.DEFINITION_TEXT -> Html.escape(Html.text(card.definition))
        Slot.SENTENCE -> card.sentence?.let { Html.escape(it.text) } ?: ""
        Slot.SENTENCE_BOLD -> card.sentence?.let(Html::bold) ?: ""
        Slot.SENTENCE_CLOZE -> card.sentence?.let(Html::cloze) ?: ""
        Slot.IMAGE -> if (card.image.isEmpty()) "" else "<img src=\"${Html.escape(card.image)}\">"
        Slot.AUDIO -> sound(card.audio)
        Slot.SENTENCE_AUDIO -> sound(card.sentenceAudio)
        Slot.SOURCE -> Html.escape(card.source)
        Slot.URL -> if (card.url.isEmpty()) "" else "<a href=\"${Html.escape(card.url)}\">${Html.escape(card.url)}</a>"
    }

    /** The fields of a note type in their order, each rendered from its slot. A field with no slot is empty. */
    fun fill(card: Card, fieldNames: List<String>, mapping: Map<String, Slot>): List<String> =
        fieldNames.map { render(card, mapping[it] ?: Slot.NONE) }

    private fun sound(name: String) = if (name.isEmpty()) "" else "[sound:$name]"

    /** The slot that a field with this name most likely wants, from the words in the name. */
    fun guess(fieldName: String): Slot {
        val n = fieldName.lowercase().replace(Regex("[^a-z0-9\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]"), "")
        return when {
            n.contains("sentenceaudio") || n.contains("contextaudio") || n.contains("audiosentence") -> Slot.SENTENCE_AUDIO
            n.contains("audio") || n.contains("sound") || n.contains("音声") -> Slot.AUDIO
            n.contains("furigana") || n.contains("ふりがな") -> Slot.FURIGANA
            n.contains("reading") || n.contains("読み") || n.contains("よみ") || n.contains("kana") || n.contains("pronunciation") -> Slot.READING
            n.contains("sentence") || n.contains("context") || n.contains("example") || n.contains("文") -> Slot.SENTENCE_BOLD
            n.contains("image") || n.contains("picture") || n.contains("screenshot") || n.contains("画像") -> Slot.IMAGE
            n.contains("definition") || n.contains("meaning") || n.contains("glossary") || n.contains("意味") || n == "back" -> Slot.DEFINITION
            n.contains("source") || n.contains("出典") -> Slot.SOURCE
            n.contains("url") || n.contains("link") -> Slot.URL
            n.contains("word") || n.contains("expression") || n.contains("単語") || n.contains("term") || n.contains("vocab") || n == "front" -> Slot.WORD
            else -> Slot.NONE
        }
    }

    /** A mapping for the fields of a note type. A slot goes to the first field that wants it. */
    fun guessAll(fieldNames: List<String>): Map<String, Slot> {
        val taken = HashSet<Slot>()
        return fieldNames.associateWith { name ->
            val slot = guess(name)
            if (slot == Slot.NONE || !taken.add(slot)) Slot.NONE else slot
        }
    }
}

/** The note type that the app makes in Anki when the user picks none. */
object NoteType {
    const val NAME = "SubRead"
    val FIELDS = listOf("Word", "Reading", "Definition", "Sentence", "Image", "Audio", "SentenceAudio", "Source")
    const val CARD = "Card 1"
    const val FRONT = "<div class=\"word\">{{Word}}</div>\n<div class=\"sentence\">{{Sentence}}</div>"
    const val BACK = "{{FrontSide}}\n<hr id=\"answer\">\n<div class=\"reading\">{{Reading}}</div>\n{{Audio}}\n" +
        "<div class=\"definition\">{{Definition}}</div>\n<div class=\"image\">{{Image}}</div>\n{{SentenceAudio}}\n" +
        "<div class=\"source\">{{Source}}</div>"
    const val CSS = ".card { font-family: sans-serif; font-size: 22px; text-align: center; color: black; background-color: white; }\n" +
        ".word { font-size: 40px; }\n.sentence { margin-top: 12px; }\n.sentence b { color: #b3261e; }\n" +
        ".reading { color: #444; }\n.definition { margin-top: 12px; text-align: left; }\n" +
        ".image img { max-width: 100%; max-height: 60vh; margin-top: 12px; }\n.source { margin-top: 12px; color: #888; font-size: 14px; }"
    val MAPPING: Map<String, Slot> = mapOf(
        "Word" to Slot.WORD,
        "Reading" to Slot.READING,
        "Definition" to Slot.DEFINITION,
        "Sentence" to Slot.SENTENCE_BOLD,
        "Image" to Slot.IMAGE,
        "Audio" to Slot.AUDIO,
        "SentenceAudio" to Slot.SENTENCE_AUDIO,
        "Source" to Slot.SOURCE,
    )
}
