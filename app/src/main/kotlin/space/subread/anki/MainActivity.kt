package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import space.subread.anki.core.Fields
import space.subread.anki.core.NoteType
import space.subread.anki.core.Source
import kotlin.concurrent.thread

/**
 * The settings: AnkiDroid, the deck and the note type, the dictionary, the capture, the
 * options, and a field to try the pop-up. Each part is on the screen from the start, so that
 * the user sees what the app does before a button is pressed.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
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

    /** The user comes back from AnkiDroid or from a dialog of Android here, so the state is read again. */
    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun draw() {
        content.removeAllViews()
        title(getString(R.string.app_name))
        note(getString(R.string.about))
        note(getString(R.string.privacy))

        val anki = AnkiClient.packageName(this)
        val allowed = anki != null && AnkiClient.hasPermission(this)
        when {
            anki == null -> step(R.string.step_anki, getString(R.string.anki_missing), getString(R.string.anki_install)) { open(ANKIDROID_INSTALL) }
            !allowed -> step(R.string.step_anki, getString(R.string.anki_no_permission), getString(R.string.anki_allow)) {
                requestPermissions(arrayOf(AnkiClient.PERMISSION), PERMISSION)
            }
            else -> step(R.string.step_anki, getString(R.string.anki_ok), null, done = true) {}
        }

        step(R.string.step_deck, getString(R.string.deck_current, deckName()), if (allowed) getString(R.string.deck_choose) else null) { chooseDeck() }
        note(modelName(), top = 12)
        if (allowed) row(button(getString(R.string.model_choose)) { chooseModel() }, button(getString(R.string.fields)) { editFields() })
        note(getString(R.string.fields_why), top = 4)

        val dictionary = DictionaryClient.installed(this)
        step(
            R.string.step_dictionary,
            getString(
                when {
                    !dictionary -> R.string.dictionary_missing
                    !DictionaryClient.answers(this) -> R.string.dictionary_old
                    else -> R.string.dictionary_ok
                },
            ),
            getString(if (dictionary) R.string.open_dictionary else R.string.install_dictionary),
            done = DictionaryClient.answers(this),
        ) {
            if (dictionary) launch(DictionaryClient.PACKAGE) else open(DICTIONARY_INSTALL)
        }

        val capturing = CaptureService.instance != null
        step(
            R.string.step_capture,
            getString(R.string.capture_why) + if (CaptureService.canCaptureAudio) "" else "\n\n" + getString(R.string.capture_no_audio),
            getString(if (capturing) R.string.capture_stop else R.string.capture_start),
            done = capturing,
        ) {
            if (capturing) CaptureService.stop(this) else startActivity(Intent(this, CaptureActivity::class.java))
            content.postDelayed({ draw() }, 500)
        }
        note(getString(R.string.capture_overlay), top = 12)
        note(getString(R.string.pad), top = 12)
        numberField(store.padMs) { store.padMs = it }

        step(R.string.step_options, getString(if (store.addAtOnce) R.string.popup_off else R.string.popup_on), getString(R.string.toggle)) {
            store.addAtOnce = !store.addAtOnce
            draw()
        }
        note(getString(if (store.skipDuplicates) R.string.duplicates_skip else R.string.duplicates_add), top = 12)
        content.addView(button(getString(R.string.toggle)) { store.skipDuplicates = !store.skipDuplicates; draw() }, narrow())
        note(getString(R.string.tags), top = 12)
        textField(store.tags) { store.tags = it }

        step(R.string.step_try, "", null) {}
        val tryField = EditText(this).apply {
            hint = getString(R.string.try_hint)
            setTextColor(Color.BLACK)
        }
        content.addView(tryField, wide())
        content.addView(button(getString(R.string.try_look_up)) {
            val text = tryField.text.toString().trim()
            // The same intent as the text selection menu, so "Try it" opens the same pop-up.
            if (text.isNotEmpty()) {
                startActivity(
                    Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClass(this, AddActivity::class.java)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text),
                )
            }
        }, narrow())

        if (BuildConfig.DONATE_LINK) content.addView(button(getString(R.string.donate)) { open(KOFI) }, wide(top = 24))
    }

    private fun deckName(): String {
        val id = store.deckId
        if (id == Store.NONE) return AnkiClient.DECK_NAME
        return AnkiClient(this).decks()[id] ?: AnkiClient.DECK_NAME
    }

    private fun modelName(): String {
        val id = store.modelId
        if (id == Store.NONE) return getString(R.string.model_own, NoteType.NAME)
        val name = AnkiClient(this).models()[id] ?: return getString(R.string.model_own, NoteType.NAME)
        return getString(R.string.model_current, name)
    }

    private fun chooseDeck() {
        thread {
            val decks = AnkiClient(this).decks().entries.sortedBy { it.value }
            runOnUiThread {
                val names = decks.map { it.value } + getString(R.string.deck_new)
                AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, which ->
                    val deck = decks.getOrNull(which)
                    if (deck != null) {
                        store.deckId = deck.key
                        draw()
                    } else {
                        newDeck()
                    }
                }.show()
            }
        }
    }

    private fun newDeck() {
        val field = EditText(this).apply { hint = getString(R.string.deck_new_name) }
        AlertDialog.Builder(this).setView(field).setPositiveButton(R.string.save) { _, _ ->
            val name = field.text.toString().trim()
            if (name.isEmpty()) return@setPositiveButton
            thread {
                val id = runCatching { AnkiClient(this).run { decks().entries.firstOrNull { it.value == name }?.key ?: addNewDeckOrNull(name) } }.getOrNull()
                runOnUiThread {
                    if (id != null) store.deckId = id else toast(getString(R.string.failed, name))
                    draw()
                }
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun AnkiClient.addNewDeckOrNull(name: String): Long? = runCatching {
        com.ichi2.anki.api.AddContentApi(this@MainActivity).addNewDeck(name)
    }.getOrNull()

    private fun chooseModel() {
        thread {
            val models = AnkiClient(this).models().entries.sortedBy { it.value }
            runOnUiThread {
                val names = listOf(getString(R.string.model_own_choice)) + models.map { it.value }
                AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, which ->
                    val model = models.getOrNull(which - 1)
                    store.modelId = model?.key ?: Store.NONE
                    // A new note type gets a fresh guess of its fields.
                    store.mapping = if (model == null) NoteType.MAPPING else emptyMap()
                    draw()
                }.show()
            }
        }
    }

    /** One dialog with the fields; a tap on a field opens the list of what can fill it. */
    private fun editFields() {
        thread {
            val anki = AnkiClient(this)
            val modelId = runCatching { anki.ensureModel(anki.ensureDeck()) }.getOrNull()
            val fields = modelId?.let { anki.fields(it) }.orEmpty()
            runOnUiThread {
                if (modelId == null || fields.isEmpty()) {
                    toast(getString(R.string.anki_no_permission))
                    return@runOnUiThread
                }
                val mapping = LinkedHashMap(anki.mapping(modelId, fields).let { m -> fields.associateWith { m[it] ?: Source.NONE } })
                showFields(fields, mapping)
            }
        }
    }

    private fun showFields(fields: List<String>, mapping: LinkedHashMap<String, Source>) {
        val labels = fields.map { getString(R.string.field_source, it, mapping[it]!!.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.fields)
            .setItems(labels.toTypedArray()) { _, which ->
                val field = fields[which]
                val sources = Source.entries
                AlertDialog.Builder(this).setTitle(field).setItems(sources.map { it.name }.toTypedArray()) { _, i ->
                    mapping[field] = sources[i]
                    showFields(fields, mapping)
                }.show()
            }
            .setPositiveButton(R.string.save) { _, _ ->
                store.mapping = mapping
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.anki_no_permission))
        }
        draw()
    }

    private fun launch(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { runCatching { startActivity(it) } }
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

    private fun note(value: String, top: Int = 8) = content.addView(TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.BLACK)
    }, wide(top))

    @SuppressLint("SetTextI18n")
    private fun step(name: Int, detail: String, action: String?, done: Boolean = false, onAction: () -> Unit) {
        content.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = getString(name) + if (done) "  ✓" else ""
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, wide(top = 12))
        if (detail.isNotEmpty()) note(detail, top = 4)
        if (action != null) content.addView(button(action, onAction), narrow())
    }

    private fun textField(value: String, onSave: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(value)
            setTextColor(Color.BLACK)
        }
        row(field.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, button(getString(R.string.save)) {
            onSave(field.text.toString())
            toast(getString(R.string.saved))
        })
    }

    // A count of milliseconds is typed as plain digits, in every locale.
    @SuppressLint("SetTextI18n")
    private fun numberField(value: Int, onSave: (Int) -> Unit) {
        val field = EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.BLACK)
        }
        row(field.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, button(getString(R.string.save)) {
            field.text.toString().toIntOrNull()?.let(onSave)
            toast(getString(R.string.saved))
        })
    }

    private fun row(vararg views: View) = content.addView(LinearLayout(this).apply { views.forEach { addView(it) } }, wide(top = 4))

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun wide(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun narrow() = LinearLayout.LayoutParams(-2, -2)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION = 1
        const val ANKIDROID_INSTALL = "https://play.google.com/store/apps/details?id=com.ichi2.anki"
        const val DICTIONARY_INSTALL = "https://github.com/equwal/subread-dictionary/releases/latest"
        const val KOFI = "https://ko-fi.com/truex"
    }
}
