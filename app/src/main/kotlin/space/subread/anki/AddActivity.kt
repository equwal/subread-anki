package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import space.subread.anki.core.Kana
import space.subread.anki.core.Sentence
import space.subread.anki.core.Sentences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes one card. The window is see-through while the parts of the card are gathered: the
 * definition and the audio from SubRead Dictionary, the sentence and the screenshot from the
 * capture service. A complete card goes to Anki at once, with a toast. A card that misses a
 * part, or one the user wants to see, shows as a sheet at the bottom of the screen.
 */
class AddActivity : Activity() {

    private lateinit var store: Store
    private lateinit var anki: AnkiDroid
    private lateinit var dictionary: Dictionary
    private lateinit var notes: Notes
    private val handler = Handler(Looper.getMainLooper())
    private var request: Request? = null
    private var draft = Draft()
    private var sheet: View? = null
    private var busy = false

    /** The user's choice on the sheet. Before the sheet: the settings. */
    private var wordAudioWanted = true
    private var sentenceAudioWanted = true

    // The fields of the sheet, when it shows.
    private var wordField: EditText? = null
    private var readingField: EditText? = null
    private var definitionField: EditText? = null
    private var sentenceField: EditText? = null
    private var tagsField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        anki = AnkiDroid(this)
        dictionary = Dictionary(this)
        notes = Notes(this, store, anki)
        begin(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        begin(intent)
    }

    private fun begin(intent: Intent) {
        draft = Draft()
        sheet?.let { (it.parent as? ViewGroup)?.removeView(it) }
        sheet = null
        sentenceAudioWanted = store.sentenceAudio
        wordAudioWanted = true
        val request = Request.of(intent, referrer?.takeIf { it.scheme == "android-app" }?.host)
        if (request == null) return finishWith(getString(R.string.no_text))
        this.request = request
        Media.clean(this)
        when {
            !anki.installed -> AlertDialog.Builder(this)
                .setMessage(R.string.anki_missing)
                .setPositiveButton(R.string.anki_get) { _, _ -> open(AnkiDroid.INSTALL) }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { finish() }
                .show()
            !anki.permitted -> requestPermissions(arrayOf(AnkiDroid.PERMISSION), PERMISSION)
            else -> start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else finishWith(getString(R.string.permission_denied))
    }

    private fun start() {
        val request = this.request ?: return
        busy = true
        thread(name = "gather") {
            runCatching { gather(request) }
            runOnUiThread {
                busy = false
                decide(request)
            }
        }
    }

    /** Fills the draft: what came with the intent first, then the dictionary and the capture service. Off the main thread. */
    private fun gather(request: Request) {
        val now = System.currentTimeMillis()
        draft.surface = request.word
        draft.word = request.word
        draft.reading = request.reading
        draft.definition = request.definition
        draft.text = request.text
        draft.source = request.source
        draft.url = request.url
        draft.tags = request.tags
        val selection = Capture.selection?.takeIf { now - it.at < RECENT_MS }
        val shot = Capture.shot?.takeIf { now - it.at < RECENT_MS && it.file.isFile }

        // The image: the one that came, else the screenshot of the selection, else a fresh one.
        draft.image = request.image?.let { Media.copy(this, it, "image", "image") }
        if (draft.image == null && store.image) {
            draft.image = shot?.let { copyOf(it.file) } ?: if (request.screenshot) freshShot() else null
        }

        // The word, the reading and the definition, from SubRead Dictionary when they did not come.
        if (draft.word.isNotEmpty() && draft.definition.isEmpty() && store.dictionary && dictionary.installed) {
            val entries = dictionary.lookup(draft.word)
            val first = entries.firstOrNull()
            if (first != null) {
                draft.word = first.expression
                if (draft.reading.isEmpty()) draft.reading = first.reading
                draft.definition = Dictionary.definition(entries)
            }
        }

        draft.sentence = sentenceFor(request, selection)

        // The word audio: the one that came, else the first source of SubRead Dictionary.
        draft.audio = request.audio?.let { Media.copy(this, it, "word", "audio") }
        if (draft.audio == null && store.audio && draft.word.isNotEmpty() && dictionary.installed) {
            draft.audio = dictionary.audio(draft.word, draft.reading)
        }
        draft.sentenceAudio = request.sentenceAudio?.let { Media.copy(this, it, "sentence", "audio") }

        if (draft.source.isEmpty()) draft.source = appName(request.caller ?: selection?.packageName ?: shot?.packageName)
    }

    /**
     * The sentence: the one that came, when it has the word and more; else the text around the
     * selection that the capture service saw; else the one that came; else the word alone.
     */
    private fun sentenceFor(request: Request, selection: Capture.Selection?): Sentence? {
        val surface = draft.surface
        if (surface.isEmpty()) return null
        val given = request.sentence
        val at = given.indexOf(surface)
        if (at >= 0 && given.length > surface.length) return Sentence(given, at, at + surface.length)
        if (selection != null) {
            if (selection.word.trim() == surface) return Sentences.around(selection.text, selection.start, selection.end)
            val found = selection.text.indexOf(surface)
            if (found >= 0) return Sentences.around(selection.text, found, found + surface.length)
        }
        if (given.isNotEmpty()) return if (at >= 0) Sentence(given, at, at + surface.length) else Sentence(given, given.length, given.length)
        return Sentence.of(surface)
    }

    /** A screenshot now, through the capture service. Null without the service, or when Android refuses. */
    private fun freshShot(): File? {
        val service = CaptureService.instance ?: return null
        val done = CountDownLatch(1)
        var out: File? = null
        handler.post {
            service.screenshot { file ->
                out = file?.let { copyOf(it) }
                done.countDown()
            }
        }
        done.await(SHOT_WAIT_MS, TimeUnit.MILLISECONDS)
        return out
    }

    /** The screenshot of the service goes when the next one comes: the card keeps its own copy. */
    private fun copyOf(file: File): File? = runCatching { file.copyTo(Media.file(this, "image", "jpg"), overwrite = true) }.getOrNull()

    private fun appName(packageName: String?): String {
        if (packageName == null || packageName == this.packageName) return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault("")
    }

    /** A complete card goes at once when the settings say so. Otherwise the sheet shows. */
    private fun decide(request: Request) {
        val incomplete = draft.word.isEmpty() || (draft.definition.isEmpty() && store.dictionary && dictionary.installed)
        if (request.show || !store.addAtOnce || incomplete) showSheet() else add()
    }

    private fun add() {
        if (busy) return
        busy = true
        sheet?.findViewWithTag<Button>(TAG_ADD)?.apply {
            isEnabled = false
            text = getString(R.string.adding)
        }
        thread(name = "add") {
            val result = runCatching {
                if (!wordAudioWanted) draft.audio = null
                val sentence = draft.sentence
                if (draft.sentenceAudio == null && sentenceAudioWanted && sentence != null) draft.sentenceAudio = speak(sentence)
                if (!sentenceAudioWanted) draft.sentenceAudio = null
                notes.add(draft)
            }
            runOnUiThread {
                busy = false
                result.onSuccess { added ->
                    val message = if (added.duplicates > 0) getString(R.string.added_again, added.word, added.duplicates) else getString(R.string.added, added.word)
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent().putExtra(Contract.EXTRA_NOTE_ID, added.noteId))
                    finish()
                }.onFailure {
                    showSheet(getString(R.string.anki_error, it.message ?: it.javaClass.simpleName))
                }
            }
        }
    }

    /** The voice of the device reads the sentence into a file. Null when it cannot. Off the main thread. */
    private fun speak(sentence: Sentence): File? {
        if (sentence.text.isBlank() || sentence.text.length > SPEECH_MAX) return null
        val file = Media.file(this, "sentence", "wav")
        if (Speech(this).synthesize(sentence.text, file, Kana.hasJapanese(sentence.text))) return file
        file.delete()
        return null
    }

    // The sheet.

    @SuppressLint("ClickableViewAccessibility") // The touch listener finds the character under the finger; it calls performClick itself.
    private fun showSheet(error: String? = null) {
        sheet?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = getString(R.string.sheet_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(smallButton(getString(R.string.settings)) { startActivity(Intent(this, MainActivity::class.java)) })
        header.addView(smallButton(getString(R.string.close)) { finish() })
        panel.addView(header, wide(0))
        if (error != null) panel.addView(label(error, Color.RED))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pick = draft.word.isEmpty() && draft.text.isNotEmpty()
        if (pick) {
            body.addView(label(getString(R.string.pick_word), Color.DKGRAY))
            body.addView(TextView(this).apply {
                text = draft.text
                textSize = 18f
                setTextColor(Color.BLACK)
                setPadding(0, dp(4), 0, dp(4))
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val at = (view as TextView).getOffsetForPosition(event.x, event.y)
                        if (at in draft.text.indices) pickWord(at)
                        view.performClick()
                    }
                    true
                }
            }, wide(4))
        }
        body.addView(label(getString(R.string.word), Color.DKGRAY, 13f))
        wordField = field(draft.word, single = true).also { body.addView(it, wide(0)) }
        body.addView(label(getString(R.string.reading), Color.DKGRAY, 13f))
        readingField = field(draft.reading, single = true).also { body.addView(it, wide(0)) }

        body.addView(label(getString(R.string.definition), Color.DKGRAY, 13f))
        definitionField = null
        if (draft.definition.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = Html.fromHtml(draft.definition, Html.FROM_HTML_MODE_COMPACT)
                textSize = 15f
                setTextColor(Color.BLACK)
            }, wide(0))
        } else {
            val why = if (dictionary.installed) R.string.no_definition_found else R.string.no_definition
            body.addView(label(getString(why), Color.DKGRAY))
            definitionField = field("", single = false).also { body.addView(it, wide(0)) }
        }

        body.addView(label(getString(R.string.sentence), Color.DKGRAY, 13f))
        sentenceField = field(draft.sentence?.text ?: "", single = false).also { body.addView(it, wide(0)) }

        body.addView(label(getString(R.string.image), Color.DKGRAY, 13f))
        val image = draft.image
        if (image != null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this).apply {
                setImageBitmap(thumbnail(image))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
            }, LinearLayout.LayoutParams(0, dp(110), 1f))
            row.addView(smallButton(getString(R.string.remove)) {
                draft.image = null
                showSheet(error)
            })
            body.addView(row, wide(0))
        } else {
            body.addView(label(getString(R.string.image_none), Color.DKGRAY))
        }

        body.addView(CheckBox(this).apply {
            text = getString(R.string.word_audio)
            isEnabled = draft.audio != null
            isChecked = draft.audio != null && wordAudioWanted
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, checked -> wordAudioWanted = checked }
        }, wide(4))
        body.addView(CheckBox(this).apply {
            text = getString(R.string.sentence_audio)
            isChecked = sentenceAudioWanted
            setTextColor(Color.BLACK)
            setOnCheckedChangeListener { _, checked -> sentenceAudioWanted = checked }
        }, wide(0))

        val deckRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        deckRow.addView(label(getString(R.string.deck, store.deckName.ifEmpty { getString(R.string.deck_default) }), Color.BLACK), LinearLayout.LayoutParams(0, -2, 1f))
        deckRow.addView(smallButton(getString(R.string.choose)) { chooseDeck { showSheet(error) } })
        body.addView(deckRow, wide(4))

        body.addView(label(getString(R.string.tags_short) + " (+ " + store.tags + ")", Color.DKGRAY, 13f))
        tagsField = field(draft.tags, single = true).also { body.addView(it, wide(0)) }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
        scroll.addView(body)
        panel.addView(scroll, wide(4))

        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(smallButton(getString(R.string.cancel)) { finish() })
        buttons.addView(Button(this).apply {
            text = getString(R.string.add)
            tag = TAG_ADD
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                readSheet()
                if (draft.word.isEmpty()) Toast.makeText(this@AddActivity, R.string.no_word, Toast.LENGTH_SHORT).show() else add()
            }
        })
        panel.addView(buttons, wide(8))

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        sheet = root
    }

    /** The user tapped a character of the text: the word there, from the dictionary or by the word breaks of the language. */
    private fun pickWord(at: Int) {
        if (busy) return
        busy = true
        val text = draft.text
        thread(name = "pick") {
            val entries = if (store.dictionary && dictionary.installed) dictionary.lookup(text.substring(at)) else emptyList()
            val first = entries.firstOrNull()
            val end = if (first != null) at + first.length else wordEnd(text, at)
            draft.surface = text.substring(at, end)
            draft.word = first?.expression ?: draft.surface
            draft.reading = first?.reading ?: ""
            draft.definition = Dictionary.definition(entries)
            draft.sentence = Sentences.around(text, at, end)
            draft.audio = if (store.audio && dictionary.installed) dictionary.audio(draft.word, draft.reading) else null
            runOnUiThread {
                busy = false
                showSheet()
            }
        }
    }

    /** The end of the word at [at], by the word breaks of the language of the text. */
    private fun wordEnd(text: String, at: Int): Int {
        val words = BreakIterator.getWordInstance()
        words.setText(text)
        val end = words.following(at)
        return if (end == BreakIterator.DONE || end <= at) minOf(text.length, at + 1) else end
    }

    /** What the user typed on the sheet goes into the draft. */
    private fun readSheet() {
        draft.word = wordField?.text?.toString()?.trim() ?: draft.word
        draft.reading = readingField?.text?.toString()?.trim() ?: draft.reading
        definitionField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { draft.definition = space.subread.anki.core.Html.escape(it).replace("\n", "<br>") }
        draft.tags = tagsField?.text?.toString()?.trim() ?: draft.tags
        val text = sentenceField?.text?.toString()?.trim() ?: return
        draft.sentence = when {
            text.isEmpty() -> null
            else -> {
                val surface = draft.surface.ifEmpty { draft.word }
                val at = text.indexOf(surface).takeIf { surface.isNotEmpty() } ?: -1
                if (at >= 0) Sentence(text, at, at + surface.length) else Sentence(text, text.length, text.length)
            }
        }
    }

    private fun chooseDeck(then: () -> Unit) {
        thread {
            val decks = runCatching { anki.decks() }.getOrDefault(emptyMap()).toList().sortedBy { it.second }
            runOnUiThread {
                if (decks.isEmpty()) return@runOnUiThread
                AlertDialog.Builder(this).setItems(decks.map { it.second }.toTypedArray()) { _, which ->
                    store.deckId = decks[which].first
                    store.deckName = decks[which].second
                    then()
                }.show()
            }
        }
    }

    private fun thumbnail(file: File) = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun open(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
    }

    private fun label(value: String, color: Int, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun field(value: String, single: Boolean) = EditText(this).apply {
        setText(value)
        setTextColor(Color.BLACK)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if (single) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = single
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun wide(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION = 1
        const val TAG_ADD = "add"
        /** A selection or a screenshot older than this is not the one the card is about. */
        const val RECENT_MS = 90_000L
        const val SHOT_WAIT_MS = 2_500L
        /** The voice reads a sentence up to this long. */
        const val SPEECH_MAX = 400
    }
}
