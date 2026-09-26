package space.subread.anki.core

/**
 * The text that the pop-up shows, and where the word starts in it. The pop-up looks up the
 * word at [offset]. A tap on another character moves the offset there.
 */
data class Scan(
    val text: String,
    /** Where the word starts in [text]. -1: the word is not in the text, so [word] is looked up alone. */
    val offset: Int,
    /** The word that the sender gave, or null. */
    val word: String?,
    /** True when [text] is the subtitle line of SubRead Overlay. */
    val fromLine: Boolean,
)

/** Chooses the text of the pop-up, and the sentence of a card in it. */
object Scans {

    /**
     * The text of the pop-up for a request:
     * - a sentence from the sender, at the word when the word is in it;
     * - else the subtitle line of the overlay that holds the selection, at the selection;
     * - else the selection itself, at its start.
     *
     * Null when there is neither a selection nor a sentence.
     */
    fun resolve(selection: String?, sentence: String?, line: String?): Scan? {
        val word = selection?.trim().orEmpty()
        val given = sentence?.trim().orEmpty()
        if (given.isNotEmpty()) {
            return if (word.isEmpty()) Scan(given, 0, null, false) else Scan(given, given.indexOf(word), word, false)
        }
        if (word.isEmpty()) return null
        val subtitle = line?.trim().orEmpty()
        val at = if (subtitle.isEmpty()) -1 else subtitle.indexOf(word)
        if (at >= 0) return Scan(subtitle, at, word, true)
        return Scan(word, 0, word, false)
    }

    /**
     * Where the first scan of the pop-up looks up: the first letter or digit of [text] at or
     * after [from]. A quote or a bracket is no word. [from] when no letter follows.
     */
    fun firstLetter(text: String, from: Int): Int {
        for (at in from until text.length) if (text[at].isLetterOrDigit()) return at
        return from
    }

    /**
     * Where [expression] is in [text], also in another form. A sender gives the dictionary form
     * (`食べる`) and a sentence with the form of the text (`食べた`). [termsAt] gives the terms that
     * start at a position of the text, with the characters that each covers: the dictionary finds
     * `食べる` at `食べた`. Null when the text holds the expression in no form.
     */
    fun locate(text: String, expression: String, termsAt: (Int) -> List<Pair<String, Int>>): IntRange? {
        if (expression.isEmpty()) return null
        val direct = text.indexOf(expression)
        if (direct >= 0) return direct until direct + expression.length
        for (at in text.indices) {
            if (text[at].isWhitespace()) continue
            val length = termsAt(at).firstOrNull { it.first == expression }?.second ?: continue
            if (length > 0) return at until minOf(text.length, at + length)
        }
        return null
    }

    /**
     * The sentence of the card for a word that starts at [start] and is [length] characters
     * long: the sentence of [text] around it. Empty when the text is the word alone: a word is
     * no sentence.
     */
    fun sentence(text: String, start: Int, length: Int): String {
        if (text.isBlank()) return ""
        val at = start.coerceIn(0, text.length - 1)
        val found = text.substring(Sentences.rangeAround(text, at)).trim()
        return if (found.length <= length) "" else found
    }
}
