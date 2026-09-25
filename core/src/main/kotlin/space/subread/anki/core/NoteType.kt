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

    /** One card: the word and its sentence in front; the rest behind. */
    val CARD_NAMES: List<String> = listOf("Card 1")

    val QUESTION_FORMATS: List<String> = listOf(
        """<div class="expression">{{Expression}}</div>
<div class="sentence">{{Sentence}}</div>""",
    )

    val ANSWER_FORMATS: List<String> = listOf(
        """{{FrontSide}}
<hr id="answer">
<div class="reading">{{Reading}}</div>
<div class="audio">{{WordAudio}} {{SentenceAudio}}</div>
<div class="definition">{{Definition}}</div>
<div class="image">{{Image}}</div>
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
.sentence { margin-top: 12px; }
.reading { font-size: 26px; }
.definition { text-align: left; margin: 12px 8px; }
.image img { max-width: 100%; max-height: 60vh; }
.source { font-size: 16px; color: #555; }
"""
}
