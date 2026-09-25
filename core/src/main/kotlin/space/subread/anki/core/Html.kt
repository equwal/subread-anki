package space.subread.anki.core

/** The little HTML that a field of a note needs. */
object Html {

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** The sentence with the word in bold. */
    fun bold(sentence: Sentence): String =
        if (sentence.word.isEmpty()) escape(sentence.text)
        else escape(sentence.before) + "<b>" + escape(sentence.word) + "</b>" + escape(sentence.after)

    /** The sentence with the word as the first cloze. */
    fun cloze(sentence: Sentence): String =
        if (sentence.word.isEmpty()) escape(sentence.text)
        else escape(sentence.before) + "{{c1::" + escape(sentence.word) + "}}" + escape(sentence.after)

    /** The text of simple HTML: the tags go, a line break becomes a new line, the entities become characters. */
    fun text(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|li|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace(Regex("[ \t]*\n[ \t\n]*"), "\n")
        .trim()
}
