package space.subread.anki

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import space.subread.anki.core.MineRequest
import space.subread.anki.core.Sentences
import kotlin.concurrent.thread

/**
 * The one way in. It takes the request from the Intent, has [Miner] fill and add the card,
 * says what happened in a toast, and closes. It draws nothing: the app below stays in view.
 *
 * The result for a caller that started it for one: `RESULT_OK` with
 * [Requests.EXTRA_NOTE_ID], or `RESULT_CANCELED` with [Requests.EXTRA_ERROR].
 */
class AddActivity : Activity() {

    private lateinit var store: Store
    private var pending: MineRequest? = null
    private var addedNoteId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val request = Requests.fromIntent(intent)
        if (request == null || (request.isEmpty && request.image == null)) {
            toast(getString(R.string.no_text))
            finishWith(null, "empty")
            return
        }
        if (AnkiClient.packageName(this) == null) {
            toast(getString(R.string.anki_missing))
            startActivity(Intent(this, MainActivity::class.java))
            finishWith(null, "no_ankidroid")
            return
        }
        if (!AnkiClient.hasPermission(this)) {
            pending = request
            requestPermissions(arrayOf(AnkiClient.PERMISSION), PERMISSION)
            return
        }
        start(request)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val request = pending ?: return
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            start(request)
        } else {
            toast(getString(R.string.anki_no_permission))
            finishWith(null, "no_permission")
        }
    }

    private fun start(request: MineRequest) {
        val image = request.image
        if (request.isEmpty && image != null) {
            // A picture alone: for the last card.
            thread {
                val result = runCatching { Miner(this).attachPicture(image) }.getOrElse { Miner.Result.Failed(it.message ?: it.toString()) }
                runOnUiThread { report(result) }
            }
            return
        }
        thread {
            val plan = runCatching { Miner(this).prepare(request) }.getOrElse { e ->
                runOnUiThread { report(Miner.Result.Failed(e.message ?: e.toString())) }
                return@thread
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (request.confirm ?: store.confirm) confirm(plan) else commit(plan, plan.entries.firstOrNull())
            }
        }
    }

    /** Shows the sentence and the terms of the dictionary; the user picks one and adds. */
    private fun confirm(plan: Miner.Plan) {
        var chosen = 0
        val media = getString(
            R.string.confirm_media,
            yesNo(plan.sentenceAudio != null), yesNo(plan.image != null), yesNo(plan.entries.firstOrNull()?.audio != null),
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(plan.sentence ?: plan.selection ?: getString(R.string.confirm_title))
            .setPositiveButton(R.string.confirm_add) { _, _ -> commit(plan, plan.entries.getOrNull(chosen)) }
            .setNegativeButton(R.string.confirm_cancel) { _, _ -> finishWith(null, "cancelled") }
            .setOnCancelListener { finishWith(null, "cancelled") }
        if (plan.entries.isEmpty()) {
            builder.setMessage((plan.selection ?: getString(R.string.confirm_sentence_only)) + "\n\n" + media)
        } else {
            val labels = plan.entries.map { it.headword + "\n" + Sentences.unescape(Sentences.stripTags(it.glossary.replace("<br>", " "))).take(80) }
            builder.setSingleChoiceItems((labels + media).toTypedArray(), 0) { dialog, i ->
                // The last line says what media the card has: it is not a choice.
                if (i < labels.size) chosen = i else (dialog as AlertDialog).listView.setItemChecked(chosen, true)
            }
        }
        builder.show()
    }

    private fun commit(plan: Miner.Plan, entry: DictionaryClient.Entry?) {
        thread {
            val result = runCatching { Miner(this).add(plan, entry) }.getOrElse { Miner.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread { report(result) }
        }
    }

    private fun report(result: Miner.Result) {
        when (result) {
            is Miner.Result.Added -> {
                toast(if (result.expression.isEmpty()) getString(R.string.added_sentence) else getString(R.string.added, result.expression))
                if (store.openDictionary && result.expression.isNotEmpty() && DictionaryClient.installed(this)) {
                    addedNoteId = result.noteId
                    openDictionary(result.expression)
                } else {
                    finishWith(result.noteId, null)
                }
            }
            is Miner.Result.Duplicate -> {
                toast(getString(R.string.duplicate, result.expression))
                finishWith(null, "duplicate")
            }
            Miner.Result.Attached -> {
                toast(getString(R.string.picture_attached))
                finishWith(null, null)
            }
            is Miner.Result.Failed -> {
                toast(getString(R.string.failed, result.why))
                finishWith(null, result.why)
            }
        }
    }

    /** Opens the pop-up of SubRead Dictionary over the app, and closes when it closes. */
    @Suppress("DEPRECATION") // The result API of AndroidX would bring AppCompat in for one call.
    private fun openDictionary(text: String) {
        val intent = Intent(DICTIONARY_LOOKUP).setPackage(DictionaryClient.PACKAGE).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivityForResult(intent, DICTIONARY) }.onFailure { finishWith(addedNoteId, null) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DICTIONARY) finishWith(addedNoteId, null)
    }

    private fun finishWith(noteId: Long?, error: String?) {
        if (noteId != null) {
            setResult(RESULT_OK, Intent().putExtra(Requests.EXTRA_NOTE_ID, noteId))
        } else {
            setResult(RESULT_CANCELED, Intent().putExtra(Requests.EXTRA_ERROR, error.orEmpty()))
        }
        finish()
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PERMISSION = 1
        private const val DICTIONARY = 2
        const val DICTIONARY_LOOKUP = "space.subread.dictionary.LOOKUP"
    }
}
