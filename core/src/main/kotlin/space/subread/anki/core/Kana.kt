package space.subread.anki.core

/** Script checks. A reading is hiragana; a word can have the same sounds in katakana. */
object Kana {

    fun isHiragana(c: Char) = c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ'

    fun isKatakana(c: Char) = c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ'

    fun isKana(c: Char) = isHiragana(c) || isKatakana(c) || c == 'ー'

    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆' || c == '〇'

    fun isJapanese(c: Char) = isKana(c) || isKanji(c)

    fun hasJapanese(text: CharSequence) = text.any(::isJapanese)

    /** Katakana to hiragana. Other characters stay. The result has the length of the text. */
    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (isKatakana(c)) (c.code - 0x60).toChar() else c)
    }
}
