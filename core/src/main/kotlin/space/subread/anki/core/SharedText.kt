package space.subread.anki.core

/** The text that an app shares. A browser adds the link of the page to the selection. */
object SharedText {

    private val LINK = Regex("""https?://\S+""")

    /** Quotes that a browser puts around the selection that it shares. */
    private val QUOTES = listOf('"' to '"', '“' to '”')

    /**
     * The selection in a shared text. Chrome and Brave share `"selection"` and the link of the
     * page: the link goes, and then the quotes around the rest. A text without a link stays as
     * it is, quotes and all: the user selected them.
     */
    fun clean(text: String): String {
        if (!LINK.containsMatchIn(text)) return text.trim()
        val rest = LINK.replace(text, "").trim()
        for ((open, close) in QUOTES) {
            if (rest.length >= 2 && rest.first() == open && rest.last() == close) return rest.substring(1, rest.length - 1).trim()
        }
        return rest
    }
}
