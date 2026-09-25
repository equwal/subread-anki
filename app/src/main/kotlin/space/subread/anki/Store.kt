package space.subread.anki

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import space.subread.anki.core.Slot

/** What the user chose. It stays when the app stops. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("anki", Context.MODE_PRIVATE)

    /** The deck the cards go to. -1: none chosen yet; the app makes a "SubRead" deck. */
    var deckId: Long
        get() = prefs.getLong("deck_id", -1)
        set(value) = prefs.edit { putLong("deck_id", value) }

    var deckName: String
        get() = prefs.getString("deck_name", "") ?: ""
        set(value) = prefs.edit { putString("deck_name", value) }

    /** The note type. -1: none chosen yet; the app makes the "SubRead" note type. */
    var modelId: Long
        get() = prefs.getLong("model_id", -1)
        set(value) = prefs.edit { putLong("model_id", value) }

    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) = prefs.edit { putString("model_name", value) }

    /** Which part of the card each field of the note type holds. Empty: guessed from the field names. */
    var mapping: Map<String, Slot>
        get() {
            val json = runCatching { JSONObject(prefs.getString("mapping", "{}") ?: "{}") }.getOrElse { JSONObject() }
            return json.keys().asSequence().associateWith { key ->
                runCatching { Slot.valueOf(json.getString(key)) }.getOrDefault(Slot.NONE)
            }
        }
        set(value) = prefs.edit { putString("mapping", JSONObject(value.mapValues { it.value.name }).toString()) }

    /** True: a card with a word and a definition goes to Anki at once. False: the card shows first. */
    var addAtOnce: Boolean
        get() = prefs.getBoolean("add_at_once", true)
        set(value) = prefs.edit { putBoolean("add_at_once", value) }

    /** True: the screenshot of the capture service goes on the card. */
    var image: Boolean
        get() = prefs.getBoolean("image", true)
        set(value) = prefs.edit { putBoolean("image", value) }

    /** True: the word audio comes from SubRead Dictionary when the sender gave none. */
    var audio: Boolean
        get() = prefs.getBoolean("audio", true)
        set(value) = prefs.edit { putBoolean("audio", value) }

    /** True: the voice of the device reads the sentence into a file for the card. */
    var sentenceAudio: Boolean
        get() = prefs.getBoolean("sentence_audio", true)
        set(value) = prefs.edit { putBoolean("sentence_audio", value) }

    /** True: the definition comes from SubRead Dictionary when the sender gave none. */
    var dictionary: Boolean
        get() = prefs.getBoolean("dictionary", true)
        set(value) = prefs.edit { putBoolean("dictionary", value) }

    /** The tags of each note, separated by spaces. */
    var tags: String
        get() = prefs.getString("tags", "subread") ?: "subread"
        set(value) = prefs.edit { putString("tags", value.trim()) }
}
