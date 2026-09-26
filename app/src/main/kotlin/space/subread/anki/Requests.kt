package space.subread.anki

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import space.subread.anki.core.MineRequest
import space.subread.anki.core.SharedText

/** Reads a [MineRequest] from each kind of Intent that [AddActivity] takes. */
object Requests {

    /** The action for another app: the extras below, any subset of them. */
    const val ACTION_ADD = "space.subread.anki.action.ADD"

    /** Each key of [MineRequest.KEYS], in upper case, after this prefix: `space.subread.anki.extra.SENTENCE`. */
    const val EXTRA_PREFIX = "space.subread.anki.extra."

    /** In the result of the activity: the id of the note in AnkiDroid (`Long`). */
    const val EXTRA_NOTE_ID = "space.subread.anki.extra.NOTE_ID"

    /** In the result of the activity: why no card was added (`String`). */
    const val EXTRA_ERROR = "space.subread.anki.extra.ERROR"

    /** The name of the extra for a key of [MineRequest]. */
    fun extraName(key: String): String = EXTRA_PREFIX + key.uppercase()

    /**
     * The request in an Intent, or null when the Intent is none of the kinds the app takes.
     * A text from the selection menu or the share sheet is the word. A picture from the
     * share sheet is the picture, with the text beside it as the word. A browser shares the
     * link of the page with the text: the link does not go on the card.
     */
    fun fromIntent(intent: Intent): MineRequest? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> MineRequest(expression = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
        Intent.ACTION_SEND -> {
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(SharedText::clean)
            MineRequest(expression = text?.ifEmpty { null }, image = stream?.toString())
        }
        Intent.ACTION_VIEW -> {
            // A web page: the query of the link. An intent: link can carry extras too; they win.
            val fromQuery = MineRequest.parseQuery(intent.data?.encodedQuery)
            MineRequest.fromMap(fromQuery + fromExtras(intent))
        }
        ACTION_ADD -> MineRequest.fromMap(fromExtras(intent))
        else -> null
    }

    /**
     * The extras of the request, as strings. A Uri, a number and a boolean are written as text.
     *
     * The extras of the intent API of the main branch are taken too, with their meaning there:
     * `WORD` is the word, `TEXT` a text with no word chosen, `AUDIO` the audio of the word,
     * `SENTENCE_AUDIO` the audio of the sentence, `SHOW` the same as `CONFIRM`. SubRead
     * Overlay and SubRead Dictionary send those.
     */
    fun fromExtras(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun text(name: String): String? {
            @Suppress("DEPRECATION") // The value can be of any type: only the untyped get tells which.
            val value = extras.get(name) ?: return null
            return if (value is Boolean) (if (value) "1" else "0") else value.toString()
        }
        for (key in MineRequest.KEYS) text(extraName(key))?.let { out[key] = it }
        val other = text(EXTRA_PREFIX + "WORD") != null || text(EXTRA_PREFIX + "TEXT") != null
        if (other) {
            text(EXTRA_PREFIX + "WORD")?.let { out[MineRequest.KEY_EXPRESSION] = it }
            text(EXTRA_PREFIX + "TEXT")?.let { out.putIfAbsent(MineRequest.KEY_SENTENCE, it) }
            text(EXTRA_PREFIX + "AUDIO")?.let { out[MineRequest.KEY_WORD_AUDIO] = it }
            out.remove(MineRequest.KEY_AUDIO)
            text(EXTRA_PREFIX + "SENTENCE_AUDIO")?.let { out[MineRequest.KEY_AUDIO] = it }
            text(EXTRA_PREFIX + "SHOW")?.let { out.putIfAbsent(MineRequest.KEY_CONFIRM, it) }
        }
        return out
    }

    /** The Intent for another app on the device: the request as extras of [ACTION_ADD]. */
    fun toIntent(request: MineRequest): Intent = Intent(ACTION_ADD).apply {
        for ((key, value) in request.toMap()) putExtra(extraName(key), value)
    }
}
