package space.subread.anki.core

/** Finds the sentence around a word, and marks the word in it. */
object Sentences {
    /** Characters that end a sentence. */
    private const val TERMINATORS = "。｡．！？!?…‥\n\r"

    /** Quotes and brackets that close a sentence after its terminator: `「猫だ。」` */
    private const val CLOSERS = "」』）)】〉》\"'’”"

    /** True for a character that ends a sentence. An ASCII period counts only before a space, so `3.5` stays one. */
    fun isTerminator(c: Char): Boolean = c in TERMINATORS

    /** True when the character at [index] ends a sentence, with the ASCII period rule applied. */
    fun endsAt(text: String, index: Int): Boolean {
        val c = text[index]
        if (c in TERMINATORS) return true
        return c == '.' && (index + 1 >= text.length || text[index + 1].isWhitespace())
    }

    private fun isLineBreak(c: Char) = c == '\n' || c == '\r'

    /**
     * The range of the sentence that holds the character at [index]. The sentence runs from
     * the character after the previous terminator (and its closers) to the next terminator
     * and its closers, both inclusive. A line break ends a sentence but is not part of it.
     * Spaces at the ends are not trimmed here.
     */
    fun rangeAround(text: String, index: Int): IntRange {
        require(index in text.indices) { "index $index is not in the text" }
        var start = index
        while (start > 0 && !endsAt(text, start - 1)) start--
        // A closer right after the previous terminator belongs to the previous sentence.
        while (start < index && text[start] in CLOSERS) start++
        var end = index
        while (end < text.length && !endsAt(text, end)) end++
        if (end < text.length && !isLineBreak(text[end])) {
            // Take the terminator, and every terminator that follows it: `…。` or `！？`, then the closers.
            while (end < text.length && endsAt(text, end) && !isLineBreak(text[end])) end++
            while (end < text.length && text[end] in CLOSERS) end++
        }
        return start until end
    }

    /**
     * The sentence as HTML, with the first occurrence of [word] in `<b>`. When the word is
     * not in the sentence, the sentence is only escaped.
     */
    fun emphasize(sentence: String, word: String?): String {
        if (word.isNullOrEmpty()) return escape(sentence)
        val at = sentence.indexOf(word)
        if (at < 0) return escape(sentence)
        return escape(sentence.substring(0, at)) + "<b>" + escape(word) + "</b>" +
            escape(sentence.substring(at + word.length))
    }

    /** Text made safe for HTML. */
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
