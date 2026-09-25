package space.subread.anki.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CardTest {

    private val card = Card(
        word = "食べる",
        reading = "たべる",
        definition = "<i>Jitendex</i><br>to eat",
        sentence = Sentences.around("パンを食べる。", 3, 6),
        image = "subread-1.jpg",
        audio = "subread-1.mp3",
        sentenceAudio = "subread-1-sentence.wav",
        source = "Voice & <VLC>",
        url = "https://example.com/?a=1&b=2",
    )

    @Test
    fun eachSlotRendersItsPart() {
        assertEquals("食べる", Fields.render(card, Slot.WORD))
        assertEquals("たべる", Fields.render(card, Slot.READING))
        assertEquals("食[た]べる", Fields.render(card, Slot.FURIGANA))
        assertEquals("<i>Jitendex</i><br>to eat", Fields.render(card, Slot.DEFINITION))
        assertEquals("Jitendex\nto eat", Fields.render(card, Slot.DEFINITION_TEXT))
        assertEquals("パンを食べる。", Fields.render(card, Slot.SENTENCE))
        assertEquals("パンを<b>食べる</b>。", Fields.render(card, Slot.SENTENCE_BOLD))
        assertEquals("パンを{{c1::食べる}}。", Fields.render(card, Slot.SENTENCE_CLOZE))
        assertEquals("<img src=\"subread-1.jpg\">", Fields.render(card, Slot.IMAGE))
        assertEquals("[sound:subread-1.mp3]", Fields.render(card, Slot.AUDIO))
        assertEquals("[sound:subread-1-sentence.wav]", Fields.render(card, Slot.SENTENCE_AUDIO))
        assertEquals("Voice &amp; &lt;VLC&gt;", Fields.render(card, Slot.SOURCE))
        assertEquals(
            "<a href=\"https://example.com/?a=1&amp;b=2\">https://example.com/?a=1&amp;b=2</a>",
            Fields.render(card, Slot.URL),
        )
        assertEquals("", Fields.render(card, Slot.NONE))
    }

    @Test
    fun anEmptyPartRendersEmpty() {
        val bare = Card("食べる")
        for (slot in Slot.entries) if (slot != Slot.WORD && slot != Slot.FURIGANA) assertEquals(slot.name, "", Fields.render(bare, slot))
        assertEquals("食べる", Fields.render(bare, Slot.FURIGANA))
    }

    @Test
    fun theFieldsOfANoteTypeFillInOrder() {
        val fields = listOf("Front", "Back", "Extra")
        val mapping = mapOf("Front" to Slot.WORD, "Back" to Slot.DEFINITION)
        assertEquals(listOf("食べる", "<i>Jitendex</i><br>to eat", ""), Fields.fill(card, fields, mapping))
    }

    @Test
    fun aFieldNameSaysWhatItWants() {
        assertEquals(Slot.WORD, Fields.guess("Expression"))
        assertEquals(Slot.WORD, Fields.guess("Front"))
        assertEquals(Slot.READING, Fields.guess("Reading"))
        assertEquals(Slot.FURIGANA, Fields.guess("Furigana"))
        assertEquals(Slot.DEFINITION, Fields.guess("Meaning"))
        assertEquals(Slot.DEFINITION, Fields.guess("Back"))
        assertEquals(Slot.SENTENCE_BOLD, Fields.guess("Sentence"))
        assertEquals(Slot.SENTENCE_BOLD, Fields.guess("例文"))
        assertEquals(Slot.IMAGE, Fields.guess("Picture"))
        assertEquals(Slot.AUDIO, Fields.guess("Word Audio"))
        assertEquals(Slot.SENTENCE_AUDIO, Fields.guess("Sentence Audio"))
        assertEquals(Slot.SOURCE, Fields.guess("Source"))
        assertEquals(Slot.URL, Fields.guess("URL"))
        assertEquals(Slot.NONE, Fields.guess("Notes"))
    }

    @Test
    fun aSlotGoesToTheFirstFieldThatWantsIt() {
        assertEquals(
            mapOf("Word" to Slot.WORD, "Word 2" to Slot.NONE, "Back" to Slot.DEFINITION),
            Fields.guessAll(listOf("Word", "Word 2", "Back")),
        )
        assertEquals(NoteType.MAPPING, Fields.guessAll(NoteType.FIELDS))
    }

    @Test
    fun htmlBecomesText() {
        assertEquals("a < b\nc & d", Html.text("<p>a &lt; b</p><br>c &amp; d"))
        assertEquals("one\ntwo", Html.text("<ul><li>one</li><li>two</li></ul>"))
    }
}
