package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import space.subread.anki.core.Fields
import space.subread.anki.core.NoteType
import space.subread.anki.core.Slot
import kotlin.concurrent.thread

/**
 * The settings, and the one screen that opens from the launcher: AnkiDroid with the deck, the
 * note type and its fields; the capture service; the audio; the card; and a field to try it.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var anki: AnkiDroid
    private lateinit var dictionary: Dictionary
    private lateinit var content: LinearLayout

    /** What AnkiDroid has, read off the main thread. Empty until it answers. */
    private var decks: Map<Long, String> = emptyMap()
    private var models: List<AnkiDroid.Model> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        anki = AnkiDroid(this)
        dictionary = Dictionary(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(content)
        })
    }

    /** The user comes back from the settings of Android and from AnkiDroid here, so the state is read again. */
    override fun onResume() {
        super.onResume()
        draw()
        load()
    }

    private fun load() {
        if (!anki.installed || !anki.permitted) return
        thread(name = "anki") {
            val result = runCatching { anki.decks() to anki.models() }
            runOnUiThread {
                result.onSuccess { (d, m) ->
                    decks = d
                    models = m
                    draw()
                }.onFailure { toast(getString(R.string.anki_error, it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    private fun draw() {
        content.removeAllViews()
        title(getString(R.string.app_name))
        note(getString(R.string.about))
        note(getString(R.string.privacy))

        // 1. AnkiDroid
        when {
            !anki.installed -> step(R.string.step_anki, getString(R.string.anki_missing), getString(R.string.anki_get)) { open(AnkiDroid.INSTALL) }
            !anki.permitted -> step(R.string.step_anki, getString(R.string.anki_permission), getString(R.string.allow)) {
                requestPermissions(arrayOf(AnkiDroid.PERMISSION), PERMISSION)
            }
            else -> {
                step(R.string.step_anki, "", null, done = true) {}
                val deckName = decks[store.deckId] ?: getString(R.string.deck_default)
                row(label(getString(R.string.deck, deckName)), button(getString(R.string.choose)) { chooseDeck() })
                val model = models.firstOrNull { it.id == store.modelId }
                row(label(getString(R.string.note_type, model?.name ?: getString(R.string.note_type_default))), button(getString(R.string.choose)) { chooseModel() })
                note(getString(R.string.fields), top = 12, bold = true)
                note(getString(R.string.fields_why), top = 2, color = Color.DKGRAY, size = 13f)
                fieldRows(model)
            }
        }

        // 2. Sentence and screenshot
        val capture = Capture.isEnabled(this)
        step(
            R.string.step_capture,
            getString(R.string.step_capture_why) + "\n\n" + getString(if (capture) R.string.capture_on else R.string.capture_off),
            getString(R.string.capture_open),
            done = capture,
        ) { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        toggle(if (store.image) R.string.image_on else R.string.image_off, store.image) { store.image = it }

        // 3. Audio
        step(R.string.step_audio, "", null) {}
        toggle(if (store.audio) R.string.audio_on else R.string.audio_off, store.audio) { store.audio = it }
        toggle(if (store.sentenceAudio) R.string.sentence_audio_on else R.string.sentence_audio_off, store.sentenceAudio) { store.sentenceAudio = it }

        // 4. The card
        step(R.string.step_card, "", null) {}
        toggle(if (store.dictionary) R.string.dictionary_on else R.string.dictionary_off, store.dictionary) { store.dictionary = it }
        note(getString(if (store.addAtOnce) R.string.at_once_on else R.string.at_once_off), top = 12)
        content.addView(button(getString(if (store.addAtOnce) R.string.at_once_show else R.string.at_once_add)) {
            store.addAtOnce = !store.addAtOnce
            draw()
        }, LinearLayout.LayoutParams(-2, -2))
        note(getString(R.string.tags), top = 12)
        val tags = field(store.tags, single = true)
        content.addView(button(getString(R.string.save)) {
            store.tags = tags.text.toString()
            toast(getString(R.string.saved))
        }, LinearLayout.LayoutParams(-2, -2))

        // 5. Try it
        step(R.string.step_try, "", null) {}
        val tryField = field("", single = false).apply { hint = getString(R.string.try_hint) }
        content.addView(button(getString(R.string.try_add)) {
            val text = tryField.text.toString().trim()
            if (text.isEmpty()) return@button
            val intent = Intent(this, AddActivity::class.java).setAction(Contract.ACTION_ADD).putExtra(Contract.EXTRA_SHOW, true)
            if (text.any { it.isWhitespace() }) intent.putExtra(Contract.EXTRA_TEXT, text) else intent.putExtra(Contract.EXTRA_WORD, text)
            startActivity(intent)
        }, LinearLayout.LayoutParams(-2, -2))

        // The other apps
        note(getString(R.string.dictionary_note), top = 32)
        val dictionaryApp = packageManager.getLaunchIntentForPackage(Dictionary.PACKAGE)
        if (dictionaryApp != null) {
            content.addView(button(getString(R.string.open_dictionary)) { runCatching { startActivity(dictionaryApp) } }, wide())
        } else {
            content.addView(button(getString(R.string.install_dictionary)) { open(Dictionary.INSTALL) }, wide())
        }
        if (BuildConfig.DONATE_LINK) content.addView(button(getString(R.string.donate)) { open(KOFI) }, wide())
    }

    /** One row per field of the note type: the name, and what goes into it. */
    private fun fieldRows(model: AnkiDroid.Model?) {
        val fields = model?.fields ?: NoteType.FIELDS
        val stored = store.mapping
        val mapping = if (fields.any { it in stored }) stored else if (model == null) NoteType.MAPPING else Fields.guessAll(fields)
        val labels = Slot.entries.map { it.label }
        for (name in fields) {
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(Slot.entries.indexOf(mapping[name] ?: Slot.NONE))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val slot = Slot.entries[position]
                        val now = store.mapping.toMutableMap()
                        if (!fields.any { it in now }) now.putAll(mapping)
                        if (now[name] != slot) {
                            now[name] = slot
                            store.mapping = now
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(name), LinearLayout.LayoutParams(0, -2, 1f))
                addView(spinner, LinearLayout.LayoutParams(0, -2, 1.4f))
            }, wide(2))
        }
    }

    private fun chooseDeck() {
        val list = decks.toList().sortedBy { it.second }
        val names = list.map { it.second } + getString(R.string.new_deck)
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, which ->
            if (which < list.size) {
                store.deckId = list[which].first
                store.deckName = list[which].second
                draw()
            } else {
                newDeck()
            }
        }.show()
    }

    private fun newDeck() {
        val name = EditText(this).apply { hint = getString(R.string.new_deck_name) }
        AlertDialog.Builder(this).setView(name).setPositiveButton(android.R.string.ok) { _, _ ->
            val value = name.text.toString().trim()
            if (value.isEmpty()) return@setPositiveButton
            thread {
                val id = runCatching { anki.addDeck(value) }.getOrNull()
                runOnUiThread {
                    if (id == null) {
                        toast(getString(R.string.error_deck))
                    } else {
                        store.deckId = id
                        store.deckName = value
                        load()
                    }
                }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseModel() {
        val list = models.filter { it.fields.isNotEmpty() }.sortedBy { it.name }
        val names = listOf(getString(R.string.note_type_default)) + list.map { "${it.name} (${it.fields.joinToString(", ")})" }
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, which ->
            val model = list.getOrNull(which - 1)
            store.modelId = model?.id ?: -1
            store.modelName = model?.name ?: ""
            store.mapping = if (model == null) emptyMap() else Fields.guessAll(model.fields)
            draw()
        }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            draw()
            load()
        }
    }

    private fun open(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // The screen is built in code: a few rows do not need a layout file each.

    private fun title(value: String) = content.addView(TextView(this).apply {
        text = value
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
    })

    private fun note(value: String, top: Int = 8, color: Int = Color.BLACK, size: Float = 15f, bold: Boolean = false) =
        content.addView(TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }, wide(top))

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.BLACK)
    }

    private fun toggle(text: Int, on: Boolean, set: (Boolean) -> Unit) {
        row(label(getString(text)), button(getString(if (on) R.string.turn_off else R.string.turn_on)) {
            set(!on)
            draw()
        })
    }

    private fun field(value: String, single: Boolean) = EditText(this).apply {
        setText(value)
        setTextColor(Color.BLACK)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if (single) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = single
    }.also { content.addView(it, wide()) }

    @SuppressLint("SetTextI18n")
    private fun step(name: Int, detail: String, action: String?, done: Boolean = false, onAction: () -> Unit) {
        content.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = getString(name) + if (done) "  ✓ ${getString(R.string.anki_allowed)}" else ""
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, wide(top = 12))
        if (detail.isNotEmpty()) note(detail, top = 4)
        if (action != null) content.addView(button(action, onAction), LinearLayout.LayoutParams(-2, -2))
    }

    private fun row(text: View, action: View) = content.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        addView(action, LinearLayout.LayoutParams(-2, -2))
    }, wide(2))

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun wide(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION = 1
        const val KOFI = "https://ko-fi.com/truex"
    }
}
