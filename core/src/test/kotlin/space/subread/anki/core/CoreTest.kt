package space.subread.anki.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of a text that a request meets: kana, kanji, an emoji, HTML, and the query separators. */
private val pieces: List<String> = ('a'..'z').map { it.toString() } +
    listOf(" ", "&", "=", "%", "+", "/", "?", "#", "<", ">", "\"", "\n", "猫", "ね", "こ", "ü", "😀", "。")

/** A text of up to [most] pieces, from [from]. */
private fun text(most: Int, from: List<String> = pieces, least: Int = 0): Arb<String> =
    Arb.list(Arb.element(from), least..most).map { it.joinToString("") }

private val anyText: Arb<String> = text(40)

/** Runs a property test. JUnit wants a test method to return nothing; `runBlocking` alone would return the context. */
private fun property(block: suspend () -> Unit) {
    runBlocking { block() }
}

private val tag: Arb<String> = Arb.stringPattern("[a-z0-9:_]{1,8}")

private val anyRequest: Arb<MineRequest> = arbitrary {
    val text = anyText.orNull(0.3)
    val number = Arb.long(0L..10_000_000L).orNull(0.3)
    MineRequest(
        expression = text.bind(),
        reading = text.bind(),
        definition = text.bind(),
        sentence = text.bind(),
        audio = text.bind(),
        audioStartMs = number.bind(),
        audioEndMs = number.bind(),
        wordAudio = text.bind(),
        image = text.bind(),
        source = text.bind(),
        tags = Arb.list(tag, 0..4).bind(),
        pitch = text.bind(),
        frequency = text.bind(),
        positionMs = number.bind(),
        cueStartMs = number.bind(),
        cueEndMs = number.bind(),
        confirm = Arb.boolean().orNull(0.3).bind(),
    )
}

/** A request without empty strings: an empty value is the same as no value after a round trip. */
private fun MineRequest.normalized(): MineRequest = copy(
    expression = expression?.ifEmpty { null },
    reading = reading?.ifEmpty { null },
    definition = definition?.ifEmpty { null },
    sentence = sentence?.ifEmpty { null },
    audio = audio?.ifEmpty { null },
    wordAudio = wordAudio?.ifEmpty { null },
    image = image?.ifEmpty { null },
    source = source?.ifEmpty { null },
    pitch = pitch?.ifEmpty { null },
    frequency = frequency?.ifEmpty { null },
)

/** The request survives the query of the URL and the extras of the Intent. */
class MineRequestTest {

    @Test
    fun theQueryRoundTrips() = property {
        checkAll(anyRequest) { request ->
            assertEquals(request.normalized(), MineRequest.fromQuery(request.toQuery()))
        }
    }

    @Test
    fun theMapRoundTrips() = property {
        checkAll(anyRequest) { request ->
            assertEquals(request.normalized(), MineRequest.fromMap(request.toMap()))
        }
    }

    @Test
    fun theUriHasTheSchemeAndTheHost() = property {
        checkAll(anyRequest) { request ->
            val uri = request.toUri()
            assertTrue(uri.startsWith("subreadanki://add?"))
            assertEquals(request.normalized(), MineRequest.fromQuery(uri.substringAfter('?')))
        }
    }

    @Test
    fun unknownKeysAndBadNumbersAreIgnored() {
        val request = MineRequest.fromQuery("expression=%E7%8C%AB&foo=bar&audio_start=abc&cue_end=&confirm=yes&tags=a+b%20%20c")
        assertEquals("猫", request.expression)
        assertNull(request.audioStartMs)
        assertNull(request.cueEndMs)
        assertEquals(true, request.confirm)
        assertEquals(listOf("a", "b", "c"), request.tags)
    }

    @Test
    fun aStrayPercentKeepsTheText() {
        assertEquals("100%", MineRequest.fromQuery("expression=100%").expression)
        assertEquals("", MineRequest.fromQuery("").expression ?: "")
        assertTrue(MineRequest.fromQuery(null).isEmpty)
    }

    @Test
    fun aRequestWithNoWordAndNoSentenceIsEmpty() {
        assertTrue(MineRequest(definition = "x").isEmpty)
        assertFalse(MineRequest(sentence = "x").isEmpty)
        assertFalse(MineRequest(expression = "x").isEmpty)
    }
}

/** Sentences around a word. */
class SentencesTest {

    private val sentenceBody: Arb<String> = text(12, listOf("a", "b", "c", "d", "猫", "ね", "こ", " ", "「", "」"), least = 1)
    private val terminator: Arb<String> = Arb.element(listOf("。", "！", "？", "!", "?", "…", "\n", "。」", "！？"))

    @Test
    fun theSentenceOfAWordIsTheSentenceItWasBuiltFrom() = property {
        checkAll(Arb.list(sentenceBody, 1..5), Arb.list(terminator, 5..5), Arb.int(0..4)) { bodies, ends, pick ->
            val sentences = bodies.mapIndexed { i, body -> body + ends[i] }
            val text = sentences.joinToString("")
            val i = pick % sentences.size
            val body = bodies[i]
            // A body that starts with a closer belongs to the sentence before it, by design.
            if (bodies.any { it.startsWith("」") }) return@checkAll
            val word = body.trim().takeIf { it.isNotEmpty() && '」' !in it && '「' !in it } ?: return@checkAll
            // The first occurrence of the word counts: skip a word that is in an earlier sentence.
            if (sentences.take(i).any { it.contains(word) }) return@checkAll
            val found = Sentences.around(text, word)
            assertEquals(sentences[i].trim(), found)
        }
    }

    @Test
    fun theSentenceIsInTheTextAndHoldsTheWord() = property {
        checkAll(anyText, text(3, listOf("a", "b", "猫", "ね", "。"), least = 1)) { text, word ->
            val found = Sentences.around(text, word)
            if (!text.contains(word)) {
                assertNull(found)
            } else {
                assertTrue("$found is in $text", text.contains(found!!))
                // A word with the end of a sentence in it is no word: the sentence of its first character is fine.
                if (word.none { Sentences.isTerminator(it) }) assertTrue("$found holds $word", found.contains(word) || word.isBlank())
            }
        }
    }

    @Test
    fun closersStayWithTheirSentence() {
        assertEquals("「猫だ。」", Sentences.around("「猫だ。」犬だ。", "猫"))
        assertEquals("犬だ。", Sentences.around("「猫だ。」犬だ。", "犬"))
        assertEquals("It rains!?", Sentences.around("Sun. It rains!? Yes.", "rains"))
        assertEquals("two", Sentences.around("one\ntwo\nthree", "two"))
        assertEquals("It is 3.5 km.", Sentences.around("Far. It is 3.5 km. Go.", "km"))
        assertEquals("「猫だ」と言った。", Sentences.around("「猫だ」と言った。犬だ。", "猫"))
    }

    @Test
    fun theEmphasisKeepsTheText() = property {
        checkAll(anyText, anyText.orNull(0.2)) { sentence, word ->
            val html = Sentences.emphasize(sentence, word)
            assertEquals(sentence, Sentences.unescape(Sentences.stripTags(html)))
            if (!word.isNullOrEmpty() && word in sentence) assertTrue(html.contains("<b>" + Sentences.escape(word) + "</b>"))
        }
    }
}

/** The clock of a player report. */
class PlayerReportTest {

    private val report: Arb<PlayerReport> = arbitrary {
        PlayerReport(
            positionMs = Arb.long(0L..36_000_000L).bind(),
            atNanos = Arb.long(0L..1_000_000_000_000L).bind(),
            speed = Arb.element(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)).bind(),
            playing = Arb.boolean().bind(),
        )
    }

    @Test
    fun theReportIsItsOwnAnchor() = property {
        checkAll(report) { r ->
            assertEquals(r.atNanos, r.wallAt(r.positionMs))
            assertEquals(r.positionMs, r.mediaAt(r.atNanos))
        }
    }

    @Test
    fun wallAndMediaAreInverse() = property {
        checkAll(report, Arb.long(0L..36_000_000L)) { r, media ->
            val back = r.mediaAt(r.wallAt(media))
            assertTrue("$media came back as $back", kotlin.math.abs(back - media) <= 1)
        }
    }

    @Test
    fun aLaterMediaTimeIsALaterWallTime() = property {
        checkAll(report, Arb.long(0L..36_000_000L), Arb.long(1L..600_000L)) { r, start, length ->
            val range = r.wallRange(start, start + length)
            assertTrue(range.last > range.first)
            assertEquals(r.wallAt(start), range.first)
            // The pad widens the range on both sides.
            val padded = r.wallRange(start, start + length, padMs = 250)
            assertTrue(padded.first < range.first && padded.last > range.last)
        }
    }

    @Test
    fun theOverlayStateLineIsRead() {
        val r = PlayerReport.parseOverlayState("playing=1;position=96153;speed=1.5;package=de.ph1b.audiobook", 7)!!
        assertEquals(96153L, r.positionMs)
        assertEquals(1.5, r.speed, 0.0)
        assertTrue(r.playing)
        assertEquals(7L, r.atNanos)
        assertEquals("de.ph1b.audiobook", PlayerReport.overlayPackage("playing=1;position=96153;speed=1.5;package=de.ph1b.audiobook"))
        assertNull(PlayerReport.parseOverlayState("error=no_player", 0))
        assertNull(PlayerReport.parseOverlayState("playing=0;speed=1.0;package=p", 0))
        assertNull(PlayerReport.parseOverlayState(null, 0))
    }
}

/** The ring of captured sound. */
class PcmRingTest {

    @Test
    fun theLastFramesComeBackInOrder() = property {
        checkAll(Arb.int(1..50), Arb.list(Arb.int(0..40), 1..30), Arb.int(1..2)) { capacity, chunks, channels ->
            val rate = 100
            val ring = PcmRing(rate, channels, capacity)
            val all = ArrayList<Short>()
            var frames = 0L
            for (chunk in chunks) {
                val samples = ShortArray(chunk * channels) { (all.size + it).toShort() }
                samples.forEach { all.add(it) }
                frames += chunk
                ring.write(samples, chunk, ring.framesToNanos(frames))
            }
            val got = ring.read(Long.MIN_VALUE, Long.MAX_VALUE)
            val held = minOf(frames, capacity.toLong()).toInt()
            assertEquals(held * channels, got.size)
            assertArrayEquals(all.takeLast(held * channels).toShortArray(), got)
            assertEquals(ring.endNanos - ring.framesToNanos(held.toLong()), ring.startNanos)
        }
    }

    @Test
    fun aRangeIsTheSliceOfTheWhole() = property {
        checkAll(Arb.int(10..200), Arb.int(0..300), Arb.int(0..300)) { capacity, a, b ->
            val rate = 1000
            val ring = PcmRing(rate, 1, capacity)
            val total = capacity + 37
            // Written in one piece: frame i ends at (i + 1) ms.
            ring.write(ShortArray(total) { it.toShort() }, total, ring.framesToNanos(total.toLong()))
            val from = minOf(a, b).toLong() * 1_000_000L
            val to = maxOf(a, b).toLong() * 1_000_000L
            val got = ring.read(from, to)
            val whole = ring.read(Long.MIN_VALUE, Long.MAX_VALUE)
            val start = (ring.startNanos / 1_000_000L).toInt()
            val lo = (maxOf(from, ring.startNanos) / 1_000_000L).toInt() - start
            val hi = (minOf(to, ring.endNanos) / 1_000_000L).toInt() - start
            val expected = if (hi > lo) whole.copyOfRange(lo, hi) else ShortArray(0)
            assertArrayEquals(expected, got)
        }
    }

    @Test
    fun anEmptyRingGivesNothing() {
        val ring = PcmRing(16_000, 1, 16_000)
        assertTrue(ring.isEmpty)
        assertEquals(0, ring.read(0, 1_000_000_000).size)
    }
}

/** Filling the fields of a note type. */
class FieldsTest {

    private val note = Note(
        expression = "猫", reading = "ねこ", definition = "<b>cat</b>", sentence = "猫がいる。",
        sentenceAudioFile = "s.m4a", wordAudioFile = "w.mp3", imageFile = "p.jpg", source = "A & B", pitch = "[1]", frequency = "12",
    )

    @Test
    fun theLapisFieldsAreGuessed() {
        val lapis = listOf(
            "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText", "MainDefinition",
            "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture", "Glossary", "Hint",
            "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard", "PitchPosition", "PitchCategories",
            "Frequency", "FreqSort", "MiscInfo",
        )
        val mapping = Fields.guess(lapis)
        assertEquals(Source.EXPRESSION, mapping["Expression"])
        assertEquals(Source.FURIGANA, mapping["ExpressionFurigana"])
        assertEquals(Source.READING, mapping["ExpressionReading"])
        assertEquals(Source.WORD_AUDIO, mapping["ExpressionAudio"])
        assertEquals(Source.DEFINITION, mapping["MainDefinition"])
        assertEquals(Source.SENTENCE_BOLD, mapping["Sentence"])
        assertEquals(Source.SENTENCE_AUDIO, mapping["SentenceAudio"])
        assertEquals(Source.IMAGE, mapping["Picture"])
        assertEquals(Source.PITCH, mapping["PitchPosition"])
        assertEquals(Source.FREQUENCY, mapping["Frequency"])
        assertEquals(Source.SOURCE, mapping["MiscInfo"])
        // The second definition field stays empty: Glossary after MainDefinition.
        assertEquals(Source.NONE, mapping["Glossary"])
        assertEquals(Source.NONE, mapping["IsSentenceCard"])
        assertEquals(Source.NONE, mapping["SentenceFurigana"])
    }

    @Test
    fun aBasicNoteTypeGetsTheWordAndTheDefinition() {
        val mapping = Fields.guess(listOf("Front", "Back"))
        assertEquals(Source.EXPRESSION, mapping["Front"])
        assertEquals(Source.DEFINITION, mapping["Back"])
        val unknown = Fields.guess(listOf("Alpha", "Beta", "Gamma"))
        assertEquals(Source.EXPRESSION, unknown["Alpha"])
        assertEquals(Source.DEFINITION, unknown["Beta"])
        assertEquals(Source.NONE, unknown["Gamma"])
    }

    @Test
    fun theRenderHasOneValueForEachField() = property {
        checkAll(Arb.list(Arb.stringPattern("[A-Za-z ]{1,12}"), 0..8)) { names ->
            val values = Fields.render(note, names, Fields.guess(names))
            assertEquals(names.size, values.size)
        }
    }

    @Test
    fun theValuesAreTheAnkiForms() {
        assertEquals("[sound:s.m4a]", Fields.value(note, Source.SENTENCE_AUDIO))
        assertEquals("[sound:w.mp3]", Fields.value(note, Source.WORD_AUDIO))
        assertEquals("<img src=\"p.jpg\">", Fields.value(note, Source.IMAGE))
        assertEquals(" 猫[ねこ]", Fields.value(note, Source.FURIGANA))
        assertEquals("<b>猫</b>がいる。", Fields.value(note, Source.SENTENCE_BOLD))
        // The word is marked as it is in the sentence, not in its dictionary form.
        val inflected = Note(expression = "食べる", sentence = "猫が食べた。", selection = "食べた")
        assertEquals("猫が<b>食べた</b>。", Fields.value(inflected, Source.SENTENCE_BOLD))
        assertEquals("A &amp; B", Fields.value(note, Source.SOURCE))
        assertEquals("x", Fields.value(note, Source.MARK))
        assertEquals("", Fields.value(Note(), Source.IMAGE))
        assertEquals("ねこ", Fields.furigana("ねこ", "ねこ"))
        assertEquals("", Fields.furigana("", "ねこ"))
    }

    @Test
    fun theOwnNoteTypeIsComplete() {
        assertEquals(NoteType.FIELDS.toSet(), NoteType.MAPPING.keys)
        val rendered = Fields.render(note, NoteType.FIELDS, NoteType.MAPPING)
        assertEquals(NoteType.FIELDS.size, rendered.size)
        for (field in NoteType.FIELDS) {
            assertTrue("$field is in the templates", (NoteType.QUESTION_FORMATS + NoteType.ANSWER_FORMATS).any { it.contains("{{$field}}") })
        }
        assertEquals(NoteType.CARD_NAMES.size, NoteType.QUESTION_FORMATS.size)
        assertEquals(NoteType.CARD_NAMES.size, NoteType.ANSWER_FORMATS.size)
    }
}

/** The text of the pop-up and the sentence of a card. */
class ScansTest {

    private val piece: Arb<String> = text(6, listOf("a", "b", "猫", "ね", "食", "べ", "た", "。", " ", "「", "」"), least = 1)

    @Test
    fun theWordIsWhereTheScanSaysItIs() = property {
        checkAll(piece.orNull(0.3), piece.orNull(0.3), piece.orNull(0.3)) { selection, sentence, line ->
            val scan = Scans.resolve(selection, sentence, line) ?: run {
                // No scan only when there is nothing to show.
                assertTrue(selection.isNullOrBlank() && sentence.isNullOrBlank())
                return@checkAll
            }
            assertTrue("empty text for $selection $sentence $line", scan.text.isNotEmpty())
            val word = scan.word
            if (word != null && scan.offset >= 0) assertTrue(scan.text.regionMatches(scan.offset, word, 0, word.length))
            if (scan.offset < 0) assertTrue(word != null && !scan.text.contains(word))
            if (scan.fromLine) assertEquals(line!!.trim(), scan.text)
        }
    }

    @Test
    fun theSenderSentenceWinsThenTheLineThenTheSelection() {
        assertEquals(Scan("猫が魚を食べた。", 4, "食べた", false), Scans.resolve("食べた", "猫が魚を食べた。", "犬だ。"))
        assertEquals(Scan("猫が魚を食べた。", -1, "食べる", false), Scans.resolve("食べる", "猫が魚を食べた。", null))
        assertEquals(Scan("猫が魚を食べた。", 4, "食べた", true), Scans.resolve("食べた", null, "猫が魚を食べた。"))
        assertEquals(Scan("食べた", 0, "食べた", false), Scans.resolve("食べた", null, "犬だ。"))
        assertEquals(Scan("猫が魚を食べた。", 0, null, false), Scans.resolve(null, "猫が魚を食べた。", null))
        assertNull(Scans.resolve(" ", null, "猫だ。"))
    }

    @Test
    fun theSentenceIsInTheText() = property {
        checkAll(anyText, Arb.int(0..50), Arb.int(0..10)) { text, start, length ->
            val sentence = Scans.sentence(text, start, length)
            assertTrue("$sentence is in $text", sentence.isEmpty() || text.contains(sentence))
            assertTrue(sentence.isEmpty() || sentence.length > length)
        }
    }

    @Test
    fun aWordAloneIsNoSentence() {
        assertEquals("", Scans.sentence("食べた", 0, 3))
        assertEquals("猫が魚を食べた。", Scans.sentence("犬だ。猫が魚を食べた。", 7, 3))
        assertEquals("", Scans.sentence("", 0, 0))
    }
}

/** Silence is not sound for a card. */
class LoudnessTest {

    @Test
    fun aConstantHasItsOwnSizeAsLoudness() = property {
        checkAll(Arb.int(-32767..32767), Arb.int(1..500)) { value, size ->
            val rms = Loudness.rms(ShortArray(size) { value.toShort() })
            assertEquals(kotlin.math.abs(value).toDouble(), rms, 1e-6)
        }
    }

    @Test
    fun speechIsNotSilenceAndZerosAre() {
        val speech = ShortArray(44_100) { (3000 * kotlin.math.sin(2 * Math.PI * 220 * it / 44_100.0)).toInt().toShort() }
        assertFalse(Loudness.isSilent(speech))
        assertTrue(Loudness.isSilent(ShortArray(44_100)))
        assertTrue(Loudness.isSilent(ShortArray(0)))
        // A noise floor of a few steps is silence.
        assertTrue(Loudness.isSilent(ShortArray(1000) { ((it % 7) - 3).toShort() }))
    }
}

/** Names of media files. */
class MediaNamesTest {

    @Test
    fun theNameIsSafeAndShort() = property {
        checkAll(anyText, Arb.long(0L..2_000_000_000_000L)) { expression, stamp ->
            val name = MediaNames.name(expression, stamp, "m4a")
            assertTrue(name, Regex("^subread_[\\p{L}\\p{N}_]+_[0-9]+\\.m4a$").matches(name))
            assertTrue(name.length <= "subread_".length + MediaNames.MAX_STEM + 1 + 13 + 4)
            assertFalse(name.contains("__"))
        }
    }

    @Test
    fun anEmptyStemIsCard() {
        assertEquals("subread_card_5.jpg", MediaNames.name("...", 5, "jpg"))
        assertEquals("subread_猫_5.mp3", MediaNames.name(" 猫 ", 5, "mp3"))
        assertEquals("subread_a_b_5", MediaNames.stem("a  b", 5))
    }
}
