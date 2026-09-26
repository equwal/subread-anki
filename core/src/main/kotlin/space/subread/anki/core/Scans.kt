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
    /** True when [text] is a subtitle line of SubRead Overlay: the sound of that line can go on the card. */
    val fromLine: Boolean,
    /** How many characters of [text] from [offset] the user selected. 0 when not known. */
    val length: Int = 0,
)

/**
 * A longer text that holds the selection of the user: the line that the user selected on
 * SubRead Overlay, or the text of the view in another app. The selection is from [start]
 * until [end] of [text].
 */
data class Around(val text: String, val start: Int, val end: Int) {
    /** True when the selection is a part of the text that is not empty. */
    val valid: Boolean get() = start in 0 until end && end <= text.length

    /** The selected text; empty for a selection that is not [valid]. */
    val selected: String get() = if (valid) text.substring(start, end) else ""
}

/** Chooses the text of the pop-up, and the sentence of a card in it. */
object Scans {

    /**
     * The text of the pop-up for a request, in this order:
     * - the line of SubRead Overlay that the user selected the word in ([row]): the sound of
     *   that line can then go on the card;
     * - a sentence from the sender that is more than the selection: SubRead Dictionary sends
     *   its pop-up text, which is often the selected word alone;
     * - the sentence around the selection in the view of another app ([view]);
     * - the subtitle line of now ([line]) that holds the word;
     * - the word alone.
     *
     * [row] and [view] count only when their selection is the word or the sentence of the
     * sender. Null when there is neither a selection nor a sentence.
     */
    fun resolve(selection: String?, sentence: String?, line: String?, row: Around? = null, view: Around? = null): Scan? {
        val word = selection?.trim().orEmpty()
        val given = sentence?.trim().orEmpty()
        val keys = listOf(word, given).filter { it.isNotEmpty() }
        fun holds(around: Around?): Around? = around?.takeIf { it.valid && it.selected.trim() in keys }

        holds(row)?.let { return Scan(it.text, it.start, word.ifEmpty { null }, fromLine = true, length = it.end - it.start) }
        val inView = holds(view)
        // The "sentence" of the sender is the selection in the view: the view has more around it.
        val givenIsSelection = inView != null && inView.selected.trim() == given
        if (given.isNotEmpty() && given != word && !givenIsSelection) {
            if (word.isEmpty()) return Scan(given, 0, null, false)
            val at = given.indexOf(word)
            return Scan(given, at, word, false, if (at >= 0) word.length else 0)
        }
        if (inView != null) {
            val cut = cut(inView.text, inView.start, inView.end - inView.start)
            if (cut.sentence.isNotEmpty() && cut.at >= 0) return Scan(cut.sentence, cut.at, word.ifEmpty { null }, false, inView.end - inView.start)
        }
        if (word.isEmpty()) return if (given.isEmpty()) null else Scan(given, 0, null, false)
        val subtitle = line?.trim().orEmpty()
        val at = if (subtitle.isEmpty()) -1 else subtitle.indexOf(word)
        if (at >= 0) return Scan(subtitle, at, word, true, word.length)
        return Scan(word, 0, word, false, word.length)
    }

    /**
     * The sentence of the card for a word that starts at [start] and is [length] characters
     * long: the sentence of [text] around it, and where the word starts in it. The sentence is
     * empty when the text is the word alone: a word is no sentence.
     */
    fun cut(text: String, start: Int, length: Int): Cut {
        if (text.isBlank()) return Cut("", -1)
        val from = start.coerceIn(0, text.length - 1)
        val to = (from + length).coerceIn(from + 1, text.length)
        val range = around(text, from, to)
        val raw = text.substring(range)
        val found = raw.trim()
        if (found.length <= length) return Cut("", -1)
        val at = from - range.first - (raw.length - raw.trimStart().length)
        return Cut(found, if (at >= 0 && at + length <= found.length) at else -1)
    }

    /**
     * The sentences of [text] that hold the characters from [start] until [end]: from the
     * sentence of the first character to the sentence of the last. A word with the end of a
     * sentence in it stays whole.
     */
    fun around(text: String, start: Int, end: Int): IntRange {
        val first = Sentences.rangeAround(text, start)
        val last = Sentences.rangeAround(text, end - 1)
        return minOf(first.first, start) until maxOf(last.last + 1, end)
    }
}

/** The sentence of a card, and where the word starts in it: -1 when the word is not in it. */
data class Cut(val sentence: String, val at: Int)
