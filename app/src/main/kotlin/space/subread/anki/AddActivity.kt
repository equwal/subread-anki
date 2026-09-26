package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import space.subread.anki.core.MineRequest
import space.subread.anki.core.Scan
import space.subread.anki.core.Scans
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

/**
 * The pop-up. It takes a text the way a dictionary does: from the text selection menu of any
 * app, the share sheet, another app, or a link. It shows the text, the terms of SubRead
 * Dictionary at the word, and a "+ Anki" button for each term. A tap on a character of the
 * text moves the word there. The card gets the term, the sentence around the word, and the
 * picture and the sound of the moment the text came in.
 *
 * A request that brings its own definition, or the setting "add at once", makes the card with
 * no pop-up: the window stays clear, and a toast says what happened.
 *
 * The result for a caller that started it for one: `RESULT_OK` with [Requests.EXTRA_NOTE_ID]
 * of the last card, or `RESULT_CANCELED` with [Requests.EXTRA_ERROR].
 */
class AddActivity : Activity() {

    private lateinit var store: Store
    private lateinit var miner: Miner

    /** One thread for the media and the cards, in order: a card waits for the media of its text. */
    private val work: ExecutorService = Executors.newSingleThreadExecutor()
    private var request = MineRequest()
    private var grab: Future<Miner.Grab>? = null

    /** A card to add with no pop-up, once AnkiDroid allows it. */
    private var pending: Scan? = null
    private var text = ""
    private var generation = 0
    private var player: MediaPlayer? = null
    private lateinit var textView: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    /** The "+ Anki" button of each term on the screen, with its expression. */
    private val addButtons = ArrayList<Pair<String, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The screen of this moment, before a window of this app draws: the app that sent the text.
        val shot: Bitmap? = CaptureService.instance?.snapshot()
        val grabbedAt = SystemClock.elapsedRealtimeNanos()
        store = Store(this)
        val parsed = Requests.fromIntent(intent)
        val pictureOnly = parsed != null && parsed.isEmpty && parsed.image != null
        val popup = parsed != null && !parsed.isEmpty && !addsAtOnce(parsed)
        // Without the pop-up, the window stays empty and clear.
        if (!popup) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(savedInstanceState)
        miner = Miner(this)
        if (parsed == null || (parsed.isEmpty && !pictureOnly)) {
            toast(getString(R.string.no_text))
            finishWith("empty")
            return
        }
        request = parsed
        if (AnkiClient.packageName(this) == null) {
            toast(getString(R.string.anki_missing))
            startActivity(Intent(this, MainActivity::class.java))
            finishWith("no_ankidroid")
            return
        }
        val image = parsed.image
        if (pictureOnly && image != null) {
            work.execute { finishAfter(runCatching { miner.attachPicture(image) }.getOrElse { Miner.Result.Failed(it.message ?: it.toString()) }) }
            return
        }
        val overlay = if (parsed.sentence.isNullOrBlank()) OverlayClient.now(this) else null
        val scan = Scans.resolve(parsed.expression, parsed.sentence, overlay?.line?.text) ?: run {
            finishWith("empty")
            return
        }
        val line = if (scan.fromLine) overlay else null
        val source = parsed.source ?: sourceName(line?.player)
        grab = work.submit(Callable { miner.grab(parsed, shot, grabbedAt, line, source) })
        val allowed = AnkiClient.hasPermission(this)
        if (!allowed) requestPermissions(arrayOf(AnkiClient.PERMISSION), PERMISSION)
        if (!popup) {
            if (allowed) addAtOnce(scan) else pending = scan
            return
        }
        draw()
        // A pop-up that closes with no card answers "cancelled"; each card replaces this answer.
        setResult(RESULT_CANCELED, Intent().putExtra(Requests.EXTRA_ERROR, "cancelled"))
        show(scan)
        work.execute {
            val grabbed = runCatching { grab?.get() }.getOrNull()
            val deck = runCatching { AnkiClient(this).deckName() }.getOrDefault(AnkiClient.DECK_NAME)
            runOnUiThread { if (!isFinishing && grabbed != null) status.text = statusLine(grabbed, deck) }
        }
    }

    /** True when the card goes to Anki with no pop-up. */
    private fun addsAtOnce(r: MineRequest): Boolean =
        r.confirm == false || (r.confirm == null && (r.definition != null || store.addAtOnce))

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val scan = pending
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (scan != null) addAtOnce(scan)
        } else {
            toast(getString(R.string.anki_no_permission))
            if (scan != null) finishWith("no_permission")
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        work.shutdown()
        super.onDestroy()
    }

    /** The card with no pop-up: the definition of the request, else the first term at the word. */
    private fun addAtOnce(scan: Scan) {
        work.execute {
            val result = runCatching {
                val word = scan.word
                // Where the word is in the text, also in another form: for the bold and the sentence.
                val found = if (scan.offset >= 0 || word == null) null else locate(scan.text, word)
                val at = if (scan.offset >= 0) scan.offset else found?.first ?: -1
                val card = if (request.definition != null) {
                    when {
                        found != null -> Miner.card(request, scan.text, found.first, found.count(), null)
                        word != null && at >= 0 -> Miner.card(request, scan.text, at, word.length, null)
                        else -> Miner.card(request, scan.text, -1, 0, null)
                    }
                } else if (at >= 0) {
                    val entry = DictionaryClient.lookup(this, scan.text, at).firstOrNull()
                    if (entry != null) {
                        Miner.card(request, scan.text, at, entry.length, entry)
                    } else {
                        val range = Miner.wordAt(scan.text, at, selected = isSelection(scan.text, at))
                        Miner.card(request, scan.text, range.first, range.count(), null)
                    }
                } else {
                    Miner.card(request, scan.text, -1, 0, DictionaryClient.lookup(this, word.orEmpty()).firstOrNull())
                }
                miner.add(grab!!.get(), card)
            }.getOrElse { Miner.Result.Failed(it.message ?: it.toString()) }
            finishAfter(result)
        }
    }

    /** True for the first scan of a text that is the selection itself: the user chose the ends of the word. */
    private fun isSelection(text: String, at: Int): Boolean = at == 0 && text == request.expression?.trim()

    /** From the work thread: says what happened, and closes. */
    private fun finishAfter(result: Miner.Result) = runOnUiThread {
        when (result) {
            is Miner.Result.Added -> {
                toast(getString(R.string.added, result.expression))
                setResult(RESULT_OK, Intent().putExtra(Requests.EXTRA_NOTE_ID, result.noteId))
                finish()
            }
            is Miner.Result.Duplicate -> {
                toast(getString(R.string.duplicate, result.expression))
                finishWith("duplicate")
            }
            Miner.Result.Attached -> {
                toast(getString(R.string.picture_attached))
                setResult(RESULT_OK, Intent().putExtra(Requests.EXTRA_NOTE_ID, store.lastNoteId))
                finish()
            }
            is Miner.Result.Failed -> {
                toast(getString(R.string.failed, result.why))
                finishWith(result.why)
            }
        }
    }

    /** Where the text is from: the player of the subtitle line, else the app that sent the text. */
    private fun sourceName(player: String?): String {
        val sender = player ?: referrer?.takeIf { it.scheme == "android-app" }?.host
        if (sender == null || sender == packageName || sender.startsWith(OverlayClient.PACKAGE)) return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sender, 0)).toString() }.getOrDefault("")
    }

    // The pop-up. The same frame as the pop-up of SubRead Dictionary: a panel at the bottom,
    // over the app that sent the text, black on white, nothing that moves.

    // The touch listener finds the character under the finger; it calls performClick itself.
    @SuppressLint("ClickableViewAccessibility")
    private fun draw() {
        val height = (resources.displayMetrics.heightPixels * 0.6).toInt()
        textView = TextView(this).apply {
            textSize = TEXT_SP + 3
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val at = (view as TextView).getOffsetForPosition(event.x, event.y)
                    if (at in text.indices) scanAt(at)
                    view.performClick()
                }
                true
            }
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), 0, dp(16), dp(8))
            text = "…"
        }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(smallButton(getString(R.string.settings)) { startActivity(Intent(this@AddActivity, MainActivity::class.java)) })
            addView(smallButton(getString(R.string.close)) { finish() })
        }
        // A long selection scrolls in its own box, at most three tenths of the pop-up, so that
        // each character stays in reach and the terms keep their room.
        val textCap = height * 3 / 10
        val textBox = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(textCap, MeasureSpec.AT_MOST))
        }.apply { addView(textView) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                addView(bar, LinearLayout.LayoutParams(-1, -2))
                addView(textBox, LinearLayout.LayoutParams(-1, -2))
                addView(status, LinearLayout.LayoutParams(-1, -2))
                addView(View(this@AddActivity).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)))
                addView(ScrollView(this@AddActivity).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
        )
        // After setContentView: it sets a floating window to WRAP_CONTENT, which makes a narrow box.
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        window.setGravity(Gravity.BOTTOM)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun show(scan: Scan) {
        text = scan.text
        textView.text = text
        if (scan.offset >= 0) {
            scanAt(Scans.firstLetter(text, scan.offset))
            return
        }
        // The word of the sender is not in the text as it is: the dictionary finds its form there.
        val word = scan.word.orEmpty()
        val run = ++generation
        thread {
            val range = locate(text, word)
            runOnUiThread {
                if (run != generation || isFinishing) return@runOnUiThread
                if (range != null) scanAt(range.first) else lookUpWord(word)
            }
        }
    }

    /** Where [word] is in [text], also in another form, found with the dictionary. Not on the UI thread. */
    private fun locate(text: String, word: String): IntRange? =
        Scans.locate(text, word) { at -> DictionaryClient.lookup(this, text, at).map { it.expression to it.length } }

    /** Looks up the terms that start at [at] in the text. */
    private fun scanAt(at: Int) {
        val run = ++generation
        mark(null)
        thread {
            val found = DictionaryClient.lookup(this, text, at)
            runOnUiThread { if (run == generation && !isFinishing) showTerms(found, at) }
        }
    }

    /** The word of the sender is not in the text in any form: its terms, with nothing marked. */
    private fun lookUpWord(word: String) {
        val run = ++generation
        thread {
            val found = DictionaryClient.lookup(this, word)
            runOnUiThread { if (run == generation && !isFinishing) showTerms(found, -1) }
        }
    }

    /** One block for each term. [at] is where the terms start in the text, -1 when not in it. */
    private fun showTerms(found: List<DictionaryClient.Entry>, at: Int) {
        results.removeAllViews()
        addButtons.clear()
        if (found.isEmpty()) {
            // No dictionary, or no term here: the word at the tap goes on the card as it is.
            val range = if (at >= 0) Miner.wordAt(text, at, selected = isSelection(text, at)) else IntRange.EMPTY
            if (at >= 0) mark(range)
            note(getString(if (DictionaryClient.answers(this)) R.string.no_term else R.string.no_dictionary), Color.DKGRAY, TEXT_SP - 3)
            val word = if (at >= 0) text.substring(range.first, range.last + 1) else request.expression.orEmpty()
            term(word, null, if (at >= 0) range.first else -1, range.count())
            return
        }
        if (at >= 0) mark(at until at + found.first().length)
        for (entry in found) term(entry.headword, entry, at, entry.length)
        markInAnki(found)
    }

    private fun term(headword: String, entry: DictionaryClient.Entry?, start: Int, length: Int) {
        results.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) })
        val add = Button(this).apply {
            text = getString(R.string.add_card)
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { addCard(this, entry, start, length) }
        }
        addButtons += (entry?.expression ?: headword) to add
        results.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@AddActivity).apply {
                    text = headword
                    textSize = TEXT_SP + 5
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (entry?.audio != null) addView(smallButton("▶") { play(entry) })
                addView(add)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        if (entry == null) return
        val meta = listOf(entry.reasons, entry.frequency, entry.pitch).filter { it.isNotEmpty() }
        if (meta.isNotEmpty()) note(meta.joinToString("   "), Color.DKGRAY, TEXT_SP - 3)
        results.addView(
            TextView(this).apply {
                text = Html.fromHtml(entry.glossary, Html.FROM_HTML_MODE_COMPACT)
                textSize = TEXT_SP
                setTextColor(Color.BLACK)
                movementMethod = LinkMovementMethod.getInstance()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
        )
    }

    /** One tap: the card goes to AnkiDroid. The button says when it is there. */
    private fun addCard(button: Button, entry: DictionaryClient.Entry?, start: Int, length: Int) {
        val grabbed = grab ?: return
        // AnkiDroid has not allowed the app yet: ask again, and keep the button for the next tap.
        if (!AnkiClient.hasPermission(this)) {
            toast(getString(R.string.anki_no_permission))
            requestPermissions(arrayOf(AnkiClient.PERMISSION), PERMISSION)
            return
        }
        button.isEnabled = false
        button.text = "…"
        val card = Miner.card(request, text, start, length, entry)
        work.execute {
            val result = runCatching { miner.add(grabbed.get(), card) }.getOrElse { Miner.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread {
                when (result) {
                    is Miner.Result.Added -> {
                        button.text = getString(R.string.added_short)
                        setResult(RESULT_OK, Intent().putExtra(Requests.EXTRA_NOTE_ID, result.noteId))
                    }
                    is Miner.Result.Duplicate -> button.text = getString(R.string.in_anki)
                    is Miner.Result.Failed -> {
                        button.isEnabled = true
                        button.text = getString(R.string.add_card)
                        toast(getString(R.string.failed, result.why))
                    }
                    Miner.Result.Attached -> Unit
                }
            }
        }
    }

    /** A term that is in the deck already gets "In Anki" in place of "+ Anki". */
    private fun markInAnki(found: List<DictionaryClient.Entry>) {
        if (!store.skipDuplicates || !AnkiClient.hasPermission(this)) return
        val run = generation
        thread {
            val anki = AnkiClient(this)
            val inAnki = found.map { it.expression }.distinct().filter { runCatching { anki.inAnki(it) }.getOrDefault(false) }.toSet()
            runOnUiThread {
                if (run != generation) return@runOnUiThread
                for ((expression, button) in addButtons) {
                    if (expression in inAnki) {
                        button.isEnabled = false
                        button.text = getString(R.string.in_anki)
                    }
                }
            }
        }
    }

    /** Marks the word in the text; null clears the mark. */
    private fun mark(range: IntRange?) {
        val shown = SpannableString(text)
        if (range != null && !range.isEmpty() && range.last < text.length) {
            shown.setSpan(BackgroundColorSpan(0xFFFFE082.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // The underline stays visible on an e-ink screen, where the colour is a light grey.
            shown.setSpan(UnderlineSpan(), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = shown
    }

    /** What the card gets besides the term: the picture, the sound, and the deck. */
    private fun statusLine(grabbed: Miner.Grab, deck: String): String {
        val parts = ArrayList<String>()
        val captureOff = CaptureService.instance == null
        if (captureOff && grabbed.picture == null && grabbed.sentenceAudio == null) {
            parts += getString(R.string.status_capture_off)
        } else {
            parts += getString(if (grabbed.picture != null) R.string.status_picture else R.string.status_no_picture)
            val ms = grabbed.soundMs
            parts += when {
                grabbed.sentenceAudio == null -> getString(R.string.status_no_sound)
                ms != null -> getString(R.string.status_sound_seconds, "%.1f".format(ms / 1000.0))
                else -> getString(R.string.status_sound)
            }
        }
        parts += getString(R.string.deck_current, deck)
        return parts.joinToString("   ")
    }

    /**
     * Plays the audio of the word from the dictionary. The file is written off the UI thread,
     * and the player prepares on its own thread. A pop-up that closed in the meantime plays
     * nothing: its onDestroy has already run, and no later player would be released.
     */
    private fun play(entry: DictionaryClient.Entry) {
        val ask = entry.audio ?: return
        thread {
            val file = DictionaryClient.audio(this, ask)?.let { bytes -> File(cacheDir, "play.tmp").apply { writeBytes(bytes) } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    toast(getString(R.string.no_audio))
                    return@runOnUiThread
                }
                player?.release()
                player = runCatching {
                    MediaPlayer().apply {
                        FileInputStream(file).use { setDataSource(it.fd) }
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                }.getOrNull()
            }
        }
    }

    private fun finishWith(error: String?) {
        setResult(RESULT_CANCELED, Intent().putExtra(Requests.EXTRA_ERROR, error.orEmpty()))
        finish()
    }

    private fun note(value: String, color: Int, size: Float) = results.addView(
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PERMISSION = 1

        /** The text size of the pop-up, in sp: the same as SubRead Dictionary. */
        private const val TEXT_SP = 17f
    }
}
