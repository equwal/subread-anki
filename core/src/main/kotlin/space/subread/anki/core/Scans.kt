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
