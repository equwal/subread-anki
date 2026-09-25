package space.subread.anki

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import space.subread.anki.core.MineRequest

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
     * share sheet is the picture, with the text beside it as the word.
     */
    fun fromIntent(intent: Intent): MineRequest? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> MineRequest(expression = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
        Intent.ACTION_SEND -> {
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            MineRequest(expression = text, image = stream?.toString())
        }
        Intent.ACTION_VIEW -> {
            // A web page: the query of the link. An intent: link can carry extras too; they win.
            val fromQuery = MineRequest.parseQuery(intent.data?.encodedQuery)
            MineRequest.fromMap(fromQuery + fromExtras(intent))
        }
        ACTION_ADD -> MineRequest.fromMap(fromExtras(intent))
        else -> null
    }

    /** The extras of the request, as strings. A Uri, a number and a boolean are written as text. */
    fun fromExtras(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in MineRequest.KEYS) {
            @Suppress("DEPRECATION") // The value can be of any type: only the untyped get tells which.
            val value = extras.get(extraName(key)) ?: continue
            out[key] = when (value) {
                is Boolean -> if (value) "1" else "0"
                else -> value.toString()
            }
        }
        return out
    }

    /** The Intent for another app on the device: the request as extras of [ACTION_ADD]. */
    fun toIntent(request: MineRequest): Intent = Intent(ACTION_ADD).apply {
        for ((key, value) in request.toMap()) putExtra(extraName(key), value)
    }
}
