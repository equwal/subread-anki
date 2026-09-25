package space.subread.anki.core

/** Names for the files that go into the media folder of Anki. */
object MediaNames {
    /** The longest part of a name taken from the expression. */
    const val MAX_STEM = 40

    /**
     * `subread_<word>_<stamp>`: the letters and digits of [expression] in any script; the
     * rest becomes `_`. An empty word becomes `card`. The stamp makes the name unique. The
     * files of one card share the stem and differ in the extension or a suffix.
     */
    fun stem(expression: String, stampMillis: Long): String {
        var word = buildString {
            for (c in expression) append(if (c.isLetterOrDigit()) c else '_')
        }.trim('_').replace(Regex("_+"), "_").take(MAX_STEM).trim('_')
        if (word.isEmpty()) word = "card"
        return "subread_${word}_$stampMillis"
    }

    /** [stem] with an extension. */
    fun name(expression: String, stampMillis: Long, extension: String): String {
        require(extension.isNotEmpty() && extension.all { it.isLetterOrDigit() }) { "bad extension $extension" }
        return "${stem(expression, stampMillis)}.$extension"
    }
}
