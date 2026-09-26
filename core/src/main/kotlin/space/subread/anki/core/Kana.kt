package space.subread.anki.core

/** The scripts of Japanese: the voice of the device reads a Japanese word with a Japanese voice. */
object Kana {

    fun isKana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c in "ゝゞヽヾー"

    fun isKanji(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in "々〆〇"

    fun hasJapanese(text: CharSequence): Boolean = text.any { isKana(it) || isKanji(it) }

    /**
     * What the voice of the device reads for a word: the reading when there is one, else the
     * expression. The reading of `食べる` is `たべる`, which has one sound only. Null for no word.
     */
    fun spoken(expression: String, reading: String): String? =
        reading.trim().ifEmpty { expression.trim() }.ifEmpty { null }
}
