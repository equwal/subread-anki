package space.subread.anki.core

/**
 * A sentence cut out of a longer text, with the place of the word in it.
 *
 * @param wordStart the index of the first character of the word in [text].
 * @param wordEnd the index after the last character of the word in [text].
 */
data class Sentence(val text: String, val wordStart: Int, val wordEnd: Int) {

    val word: String get() = text.substring(wordStart, wordEnd)

    val before: String get() = text.substring(0, wordStart)

    val after: String get() = text.substring(wordEnd)

    companion object {
        /** A sentence that is the word alone. */
        fun of(word: String) = Sentence(word, 0, word.length)
    }
}

/** Cuts the sentence around a word out of the text of a screen, a page or a subtitle file. */
object Sentences {

    /** Characters that end a sentence. A full stop ends one only before a space or at the end. */
    private const val ENDS = "。．！？!?\n\r"

    /** An end mark between an opener and its closer ends a quoted sentence, not the sentence around it. */
    private const val OPENERS = "「『（(【〔［[“‘"
    private const val CLOSERS = "」』）)】〕］]”’"

    /** The most characters kept on each side of the word when no end is near it. */
    const val MAX_SIDE = 120

    /**
     * The sentence of [text] that has the characters from [start] to [end]. The cut goes from
     * the end mark before the word to the end mark after it, with the closing quote or bracket
     * that follows that mark. Spaces at the two ends are dropped.
     */
    fun around(text: String, start: Int, end: Int): Sentence {
        val wordStart = start.coerceIn(0, text.length)
        val wordEnd = end.coerceIn(wordStart, text.length)

        var from = 0
        var depth = 0
        for (i in wordStart - 1 downTo 0) {
            val c = text[i]
            when {
                c in CLOSERS -> depth++
                c in OPENERS -> depth = maxOf(0, depth - 1)
                depth == 0 && isEnd(text, i) -> {
                    from = i + 1
                    break
                }
            }
        }
        // The closers after the end mark before the word belong to the sentence before.
        while (from < wordStart && text[from] in CLOSERS) from++
        if (from < wordStart - MAX_SIDE) from = wordStart - MAX_SIDE

        var to = text.length
        depth = 0
        for (i in wordEnd until text.length) {
            val c = text[i]
            when {
                c in OPENERS -> depth++
                c in CLOSERS -> depth = maxOf(0, depth - 1)
                depth == 0 && isEnd(text, i) -> {
                    to = if (c == '\n' || c == '\r') i else i + 1
                    while (to < text.length && text[to] in CLOSERS) to++
                    break
                }
            }
        }
        if (to > wordEnd + MAX_SIDE) to = wordEnd + MAX_SIDE

        while (from < wordStart && text[from].isWhitespace()) from++
        while (to > wordEnd && text[to - 1].isWhitespace()) to--
        return Sentence(text.substring(from, to), wordStart - from, wordEnd - from)
    }

    internal fun isEnd(text: String, at: Int): Boolean {
        val c = text[at]
        if (c in ENDS) return true
        return c == '.' && (at + 1 == text.length || text[at + 1].isWhitespace())
    }
}
