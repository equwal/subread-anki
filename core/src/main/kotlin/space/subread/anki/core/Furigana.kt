package space.subread.anki.core

/**
 * Writes a word with its reading the way the furigana filter of Anki reads it: `食[た]べる`,
 * `日本語[にほんご]`, `食[た]べ 物[もの]`. The kana of the word stay outside the brackets. Each run
 * of kanji gets the part of the reading that is its own. A space separates a bracket group from
 * the text before it; Anki does not show the space.
 */
object Furigana {

    fun bracket(word: String, reading: String): String {
        if (reading.isEmpty() || word.isEmpty()) return word
        val kana = Kana.toHiragana(reading)
        if (Kana.toHiragana(word) == kana || word.none(Kana::isKanji)) return word
        return align(word, reading, kana) ?: "$word[$reading]"
    }

    /**
     * The runs of the word, kanji and not kanji, in order. Each kana run must be in the reading
     * at its place; the reading of a kanji run goes up to the next kana run of the word. Null
     * when the reading does not fit the word.
     */
    private fun align(word: String, reading: String, kana: String): String? {
        val runs = runs(word)
        val out = StringBuilder()
        var at = 0
        for ((i, run) in runs.withIndex()) {
            val (text, kanji) = run
            if (!kanji) {
                if (!kana.startsWith(Kana.toHiragana(text), at)) return null
                out.append(text)
                at += text.length
                continue
            }
            val stop = if (i == runs.lastIndex) {
                kana.length
            } else {
                // A kanji reads as one kana at least, so the next run starts after this one.
                val found = kana.indexOf(Kana.toHiragana(runs[i + 1].first), at + 1)
                if (found < 0) return null
                found
            }
            if (stop <= at) return null
            if (out.isNotEmpty()) out.append(' ')
            out.append(text).append('[').append(reading, at, stop).append(']')
            at = stop
        }
        return if (at == kana.length) out.toString() else null
    }

    private fun runs(word: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var start = 0
        for (i in 1..word.length) {
            if (i == word.length || Kana.isKanji(word[i]) != Kana.isKanji(word[start])) {
                out.add(word.substring(start, i) to Kana.isKanji(word[start]))
                start = i
            }
        }
        return out
    }
}
