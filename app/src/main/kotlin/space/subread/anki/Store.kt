package space.subread.anki

import android.content.Context
import androidx.core.content.edit
import space.subread.anki.core.Source

/** What the user chose. It stays when the app stops. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("anki", Context.MODE_PRIVATE)

    /** The deck in AnkiDroid. [NONE] until the user chose one: the app then makes "SubRead". */
    var deckId: Long
        get() = prefs.getLong("deck_id", NONE)
        set(value) = prefs.edit { putLong("deck_id", value) }

    /** The note type in AnkiDroid. [NONE]: the note type that the app makes. */
    var modelId: Long
        get() = prefs.getLong("model_id", NONE)
        set(value) = prefs.edit { putLong("model_id", value) }

    /** What fills each field of the chosen note type. Empty: the app guesses from the field names. */
    var mapping: Map<String, Source>
        get() = prefs.getString("mapping", "").orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) null else line.substring(0, tab) to (runCatching { Source.valueOf(line.substring(tab + 1)) }.getOrNull() ?: Source.NONE)
        }.toMap()
        set(value) = prefs.edit { putString("mapping", value.entries.joinToString("\n") { (field, source) -> "$field\t${source.name}" }) }

    /** Anki tags for each card, separated by spaces. */
    var tags: String
        get() = prefs.getString("tags", "subread") ?: "subread"
        set(value) = prefs.edit { putString("tags", value.trim()) }

    /** True: the app shows the card before it adds it. False: one tap adds. */
    var confirm: Boolean
        get() = prefs.getBoolean("confirm", false)
        set(value) = prefs.edit { putBoolean("confirm", value) }

    /** True: a word that is in the note type already is not added again. */
    var skipDuplicates: Boolean
        get() = prefs.getBoolean("skip_duplicates", true)
        set(value) = prefs.edit { putBoolean("skip_duplicates", value) }

    /** True: after the card, the pop-up of SubRead Dictionary opens for the word. */
    var openDictionary: Boolean
        get() = prefs.getBoolean("open_dictionary", false)
        set(value) = prefs.edit { putBoolean("open_dictionary", value) }

    /** Sound before and after the subtitle line in the clip, in milliseconds. */
    var padMs: Int
        get() = prefs.getInt("pad_ms", 250)
        set(value) = prefs.edit { putInt("pad_ms", value.coerceIn(0, 3000)) }

    /** The last card that the app added: a picture shared later goes on it. */
    var lastNoteId: Long
        get() = prefs.getLong("last_note_id", NONE)
        set(value) = prefs.edit { putLong("last_note_id", value) }

    /** When the last card was added, `System.currentTimeMillis()`. */
    var lastNoteAt: Long
        get() = prefs.getLong("last_note_at", 0)
        set(value) = prefs.edit { putLong("last_note_at", value) }

    companion object {
        const val NONE = -1L
    }
}
