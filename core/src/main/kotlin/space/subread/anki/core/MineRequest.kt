package space.subread.anki.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One request to add a card. Every part is optional: the app fills what is missing from
 * the overlay, the dictionary and the capture. A request with no expression and no
 * sentence is empty and is refused.
 *
 * The same keys are the query of `subreadanki://add?...` and, with the prefix
 * `space.subread.anki.extra.` and in upper case, the extras of the Intent.
 */
data class MineRequest(
    /** The word, as it is in the text. */
    val expression: String? = null,
    /** The reading of the word (kana, pinyin, ...). */
    val reading: String? = null,
    /** The definition, HTML or plain text. */
    val definition: String? = null,
    /** The sentence that the word is in, plain text. */
    val sentence: String? = null,
    /** A Uri of the audio of the sentence: `content://`, `https://`, `http://` or `data:`. */
    val audio: String? = null,
    /** With [audio]: the start of the clip in the audio, in milliseconds. */
    val audioStartMs: Long? = null,
    /** With [audio]: the end of the clip in the audio, in milliseconds. */
    val audioEndMs: Long? = null,
    /** A Uri of the audio of the word alone. */
    val wordAudio: String? = null,
    /** A Uri of the picture. */
    val image: String? = null,
    /** Where the sentence is from: the title of the book or the video. */
    val source: String? = null,
    /** Anki tags. A tag has no space in it. */
    val tags: List<String> = emptyList(),
    /** Pitch accent, as text. */
    val pitch: String? = null,
    /** Frequency, as text. */
    val frequency: String? = null,
    /** The position of the player when the user tapped, in milliseconds. */
    val positionMs: Long? = null,
    /** The start of the subtitle line in the media, in milliseconds. */
    val cueStartMs: Long? = null,
    /** The end of the subtitle line in the media, in milliseconds. */
    val cueEndMs: Long? = null,
    /** True: show the card before it is added, whatever the setting says. False: add at once. */
    val confirm: Boolean? = null,
) {
    /** True when there is nothing to make a card from. */
    val isEmpty: Boolean get() = expression.isNullOrBlank() && sentence.isNullOrBlank()

    /** The request as the query of `subreadanki://add`. Keys with no value are left out. */
    fun toQuery(): String = toMap().entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    /** The request as `subreadanki://add?...`. */
    fun toUri(): String = "$SCHEME://$HOST?${toQuery()}"

    /** The request as key and value strings. Keys with no value are left out. */
    fun toMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(key: String, value: String?) {
            if (value != null) out[key] = value
        }
        put(KEY_EXPRESSION, expression)
        put(KEY_READING, reading)
        put(KEY_DEFINITION, definition)
        put(KEY_SENTENCE, sentence)
        put(KEY_AUDIO, audio)
        put(KEY_AUDIO_START, audioStartMs?.toString())
        put(KEY_AUDIO_END, audioEndMs?.toString())
        put(KEY_WORD_AUDIO, wordAudio)
        put(KEY_IMAGE, image)
        put(KEY_SOURCE, source)
        if (tags.isNotEmpty()) out[KEY_TAGS] = tags.joinToString(" ")
        put(KEY_PITCH, pitch)
        put(KEY_FREQUENCY, frequency)
        put(KEY_POSITION, positionMs?.toString())
        put(KEY_CUE_START, cueStartMs?.toString())
        put(KEY_CUE_END, cueEndMs?.toString())
        put(KEY_CONFIRM, confirm?.let { if (it) "1" else "0" })
        return out
    }

    companion object {
        const val SCHEME = "subreadanki"
        const val HOST = "add"

        const val KEY_EXPRESSION = "expression"
        const val KEY_READING = "reading"
        const val KEY_DEFINITION = "definition"
        const val KEY_SENTENCE = "sentence"
        const val KEY_AUDIO = "audio"
        const val KEY_AUDIO_START = "audio_start"
        const val KEY_AUDIO_END = "audio_end"
        const val KEY_WORD_AUDIO = "word_audio"
        const val KEY_IMAGE = "image"
        const val KEY_SOURCE = "source"
        const val KEY_TAGS = "tags"
        const val KEY_PITCH = "pitch"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_POSITION = "position"
        const val KEY_CUE_START = "cue_start"
        const val KEY_CUE_END = "cue_end"
        const val KEY_CONFIRM = "confirm"

        /** Every key, in the order of the documentation. */
        val KEYS: List<String> = listOf(
            KEY_EXPRESSION, KEY_READING, KEY_DEFINITION, KEY_SENTENCE, KEY_AUDIO, KEY_AUDIO_START,
            KEY_AUDIO_END, KEY_WORD_AUDIO, KEY_IMAGE, KEY_SOURCE, KEY_TAGS, KEY_PITCH, KEY_FREQUENCY,
            KEY_POSITION, KEY_CUE_START, KEY_CUE_END, KEY_CONFIRM,
        )

        /**
         * Reads a request from key and value strings. Unknown keys are ignored. An empty
         * value, or a number that does not parse, counts as absent.
         */
        fun fromMap(map: Map<String, String>): MineRequest {
            fun text(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
            fun number(key: String): Long? = text(key)?.trim()?.toLongOrNull()
            return MineRequest(
                expression = text(KEY_EXPRESSION),
                reading = text(KEY_READING),
                definition = text(KEY_DEFINITION),
                sentence = text(KEY_SENTENCE),
                audio = text(KEY_AUDIO),
                audioStartMs = number(KEY_AUDIO_START),
                audioEndMs = number(KEY_AUDIO_END),
                wordAudio = text(KEY_WORD_AUDIO),
                image = text(KEY_IMAGE),
                source = text(KEY_SOURCE),
                tags = text(KEY_TAGS)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList(),
                pitch = text(KEY_PITCH),
                frequency = text(KEY_FREQUENCY),
                positionMs = number(KEY_POSITION),
                cueStartMs = number(KEY_CUE_START),
                cueEndMs = number(KEY_CUE_END),
                confirm = when (text(KEY_CONFIRM)?.trim()?.lowercase()) {
                    null -> null
                    "1", "true", "yes" -> true
                    else -> false
                },
            )
        }

        /**
         * Reads a request from the query of `subreadanki://add?...`, percent-encoded. A pair
         * without `=` is a key with an empty value. `+` is a space, as `URLEncoder` writes it.
         */
        fun fromQuery(query: String?): MineRequest = fromMap(parseQuery(query))

        /** The pairs of a percent-encoded query, decoded. The last value of a repeated key wins. */
        fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                out[decode(key)] = decode(value)
            }
            return out
        }

        // The charset goes by name: the Charset overloads are Java 10, and Android has Java 8 here.
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            // A stray `%`: keep the text as it is instead of losing the request.
            value
        }
    }
}
