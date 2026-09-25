package space.subread.anki.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    private fun cut(text: String, word: String): Sentence {
        val at = text.indexOf(word)
        assertTrue("the text has the word", at >= 0)
        return Sentences.around(text, at, at + word.length)
    }

    @Test
    fun theSentenceGoesFromTheEndMarkBeforeToTheEndMarkAfter() {
        val s = cut("今日は天気がいい。明日は雨だ。また晴れる。", "明日")
        assertEquals("明日は雨だ。", s.text)
        assertEquals("明日", s.word)
        assertEquals(0, s.wordStart)
    }

    @Test
    fun aQuoteKeepsItsSentence() {
        assertEquals("「うん」", cut("「行こう」と言った。「うん」", "うん").text)
        assertEquals("彼は「はい。」と答えた。", cut("彼は「はい。」と答えた。次の日。", "答え").text)
        assertEquals("彼は「はい。」", cut("彼は「はい。」と答えた。", "はい").text)
    }

    @Test
    fun aFullStopEndsASentenceOnlyBeforeASpace() {
        assertEquals("Then he left.", cut("He said hi. Then he left. Done", "left").text)
        assertEquals("See v1.2 now.", cut("Old. See v1.2 now. New", "now").text)
    }

    @Test
    fun aNewLineEndsASentenceAndIsNotKept() {
        val s = cut("line one\nline two\nline three", "two")
        assertEquals("line two", s.text)
        assertEquals(5, s.wordStart)
    }

    @Test
    fun spacesAtTheEndsGo() {
        assertEquals("Hello there!", cut("Yes.   Hello there!  \n", "there").text)
    }

    @Test
    fun aLongTextWithNoEndMarkIsCutAroundTheWord() {
        val text = "あ".repeat(400)
        val s = Sentences.around(text, 200, 202)
        assertEquals(Sentences.MAX_SIDE + 2 + Sentences.MAX_SIDE, s.text.length)
        assertEquals(Sentences.MAX_SIDE, s.wordStart)
    }

    @Test
    fun aRangeOutsideTheTextIsClamped() {
        val s = Sentences.around("abc", -3, 10)
        assertEquals("abc", s.text)
        assertEquals("abc", s.word)
    }

    @Test
    fun theWordStaysAndTheSentenceIsAPieceOfTheText() = runBlocking<Unit> {
        val chars = "あいう漢字。！？「」『』（）. abcX\n".toList()
        val texts = Arb.list(Arb.element(chars), 0..60).map { it.joinToString("") }
        checkAll(texts, Arb.int(-2..62), Arb.int(0..10)) { text, start, length ->
            val wordStart = start.coerceIn(0, text.length)
            val wordEnd = (wordStart + length).coerceIn(wordStart, text.length)
            val s = Sentences.around(text, start, wordStart + length)

            assertEquals(text.substring(wordStart, wordEnd), s.word)
            val from = wordStart - s.wordStart
            assertTrue("the sentence is at $from in the text", text.regionMatches(from, s.text, 0, s.text.length))
            assertEquals(wordEnd - wordStart, s.wordEnd - s.wordStart)
            // No end mark stands before the word, outside a quote.
            var depth = 0
            for (i in wordStart - 1 downTo from) {
                val c = text[i]
                if (c in "」』）)") depth++ else if (c in "「『（(") depth = maxOf(0, depth - 1)
                if (depth == 0) assertFalse("no end mark at $i in '${s.text}'", Sentences.isEnd(text, i))
            }
        }
    }
}
