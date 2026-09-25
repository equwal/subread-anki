package space.subread.anki.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaTest {

    @Test
    fun theKanaOfTheWordStayOutsideTheBrackets() {
        assertEquals("食[た]べる", Furigana.bracket("食べる", "たべる"))
        assertEquals("日本語[にほんご]", Furigana.bracket("日本語", "にほんご"))
        assertEquals("食[た]べ 物[もの]", Furigana.bracket("食べ物", "たべもの"))
        assertEquals("引[ひ]っ 越[こ]す", Furigana.bracket("引っ越す", "ひっこす"))
        assertEquals("見[み]に 行[い]く", Furigana.bracket("見に行く", "みにいく"))
        assertEquals("お 茶[ちゃ]", Furigana.bracket("お茶", "おちゃ"))
        assertEquals("取[と]り 扱[あつか]い", Furigana.bracket("取り扱い", "とりあつかい"))
        assertEquals("思[おも]い 出[で]", Furigana.bracket("思い出", "おもいで"))
        assertEquals("生[い]き 生[い]き", Furigana.bracket("生き生き", "いきいき"))
    }

    @Test
    fun aWordWithNoKanjiIsItself() {
        assertEquals("たべる", Furigana.bracket("たべる", "たべる"))
        assertEquals("コーヒー", Furigana.bracket("コーヒー", "こーひー"))
        assertEquals("サボる", Furigana.bracket("サボる", "さぼる"))
        assertEquals("食べる", Furigana.bracket("食べる", ""))
    }

    @Test
    fun aReadingThatDoesNotFitGoesOnTheWholeWord() {
        assertEquals("食べる[のむ]", Furigana.bracket("食べる", "のむ"))
        assertEquals("3人[さんにん]", Furigana.bracket("3人", "さんにん"))
        assertEquals("食べる[たべ]", Furigana.bracket("食べる", "たべ"))
    }

    @Test
    fun theWordAndTheReadingComeBackOutOfTheBrackets() = runBlocking<Unit> {
        val kanji = "食日本語物人見行".toList()
        val kana = "たべものにほんごひっこすみいくおちゃ".toList()
        val words = Arb.list(Arb.element(kanji + kana), 1..6).map { it.joinToString("") }.filter { it.any(Kana::isKanji) }
        val readings = Arb.list(Arb.element(kana), 1..8).map { it.joinToString("") }
        checkAll(words, readings) { word, reading ->
            val out = Furigana.bracket(word, reading)
            assertEquals(word, out.replace(Regex("\\[[^\\]]*\\]"), "").replace(" ", ""))
            assertEquals(reading, readingOf(out))
        }
    }

    /** Reads the furigana text back: each bracket group is the reading of the kanji before it. */
    private fun readingOf(furigana: String): String = furigana.split(' ').joinToString("") { part ->
        val open = part.indexOf('[')
        if (open < 0) part else part.substring(open + 1, part.indexOf(']')) + part.substring(part.indexOf(']') + 1)
    }
}
