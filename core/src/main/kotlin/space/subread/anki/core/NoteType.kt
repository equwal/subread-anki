package space.subread.anki.core

/** The note type that the app makes in AnkiDroid when the user has none to choose. */
object NoteType {
    const val NAME = "SubRead Anki"

    const val FIELD_EXPRESSION = "Expression"
    const val FIELD_READING = "Reading"
    const val FIELD_DEFINITION = "Definition"
    const val FIELD_SENTENCE = "Sentence"
    const val FIELD_SENTENCE_AUDIO = "SentenceAudio"
    const val FIELD_WORD_AUDIO = "WordAudio"
    const val FIELD_IMAGE = "Image"
    const val FIELD_SOURCE = "Source"

    /** The fields, in order. The first is the sort field. */
    val FIELDS: List<String> = listOf(
        FIELD_EXPRESSION, FIELD_READING, FIELD_DEFINITION, FIELD_SENTENCE,
        FIELD_SENTENCE_AUDIO, FIELD_WORD_AUDIO, FIELD_IMAGE, FIELD_SOURCE,
    )

    /** The fields, with what fills each. */
    val MAPPING: Map<String, Source> = mapOf(
        FIELD_EXPRESSION to Source.EXPRESSION,
        FIELD_READING to Source.READING,
        FIELD_DEFINITION to Source.DEFINITION,
        FIELD_SENTENCE to Source.SENTENCE_BOLD,
        FIELD_SENTENCE_AUDIO to Source.SENTENCE_AUDIO,
        FIELD_WORD_AUDIO to Source.WORD_AUDIO,
        FIELD_IMAGE to Source.IMAGE,
        FIELD_SOURCE to Source.SOURCE,
    )

    /**
     * One card. In front: the sentence with the word in bold, the picture and the sound of the
     * sentence; a card with no sentence shows the word. Behind: the word with its reading and
     * its audio, the definition, and the source.
     */
    val CARD_NAMES: List<String> = listOf("Card 1")

    val QUESTION_FORMATS: List<String> = listOf(
        """{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
{{^Sentence}}<div class="expression">{{Expression}}</div>{{/Sentence}}
<div class="image">{{Image}}</div>
<div class="audio">{{SentenceAudio}}</div>""",
    )

    val ANSWER_FORMATS: List<String> = listOf(
        """{{FrontSide}}
<hr id="answer">
{{#Sentence}}<div class="expression">{{Expression}}</div>{{/Sentence}}
<div class="reading">{{Reading}}</div>
<div class="audio">{{WordAudio}}</div>
<div class="definition">{{Definition}}</div>
<div class="source">{{Source}}</div>""",
    )

    /** Black on white, large, still: readable on an e-ink screen. */
    const val CSS = """.card {
  font-family: sans-serif;
  font-size: 22px;
  text-align: center;
  color: black;
  background-color: white;
}
.expression { font-size: 44px; }
.sentence { font-size: 28px; margin: 12px 8px; }
.reading { font-size: 26px; }
.definition { text-align: left; margin: 12px 8px; }
.image img { max-width: 100%; max-height: 60vh; }
.source { font-size: 16px; color: #555; }
"""

    /**
     * The templates of the first version: the word and the sentence in front; the picture and
     * both audio files behind. The app changes a note type that still has them to the templates
     * above. A note type that the user changed stays as it is.
     */
    val OLD_QUESTION_FORMATS: List<String> = listOf(
        """<div class="expression">{{Expression}}</div>
<div class="sentence">{{Sentence}}</div>""",
    )

    val OLD_ANSWER_FORMATS: List<String> = listOf(
        """{{FrontSide}}
<hr id="answer">
<div class="reading">{{Reading}}</div>
<div class="audio">{{WordAudio}} {{SentenceAudio}}</div>
<div class="definition">{{Definition}}</div>
<div class="image">{{Image}}</div>
<div class="source">{{Source}}</div>""",
    )

    const val OLD_CSS = """.card {
  font-family: sans-serif;
  font-size: 22px;
  text-align: center;
  color: black;
  background-color: white;
}
.expression { font-size: 44px; }
.sentence { margin-top: 12px; }
.reading { font-size: 26px; }
.definition { text-align: left; margin: 12px 8px; }
.image img { max-width: 100%; max-height: 60vh; }
.source { font-size: 16px; color: #555; }
"""

    /**
     * The new question and answer format of template [ord], when its stored formats are the
     * formats of the first version; else null: the user changed the template, or it is new.
     * The formats are compared without the difference of the line ends.
     */
    fun upgrade(ord: Int, question: String, answer: String): Pair<String, String>? {
        fun same(a: String, b: String) = a.replace("\r\n", "\n").trim() == b.replace("\r\n", "\n").trim()
        val oldQuestion = OLD_QUESTION_FORMATS.getOrNull(ord) ?: return null
        val oldAnswer = OLD_ANSWER_FORMATS.getOrNull(ord) ?: return null
        if (!same(question, oldQuestion) || !same(answer, oldAnswer)) return null
        return QUESTION_FORMATS[ord] to ANSWER_FORMATS[ord]
    }

    /** The new CSS when the stored CSS is the CSS of the first version; else null. */
    fun upgradeCss(css: String): String? = if (css.replace("\r\n", "\n").trim() == OLD_CSS.trim()) CSS else null
}
