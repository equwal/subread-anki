package space.subread.anki

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.ImageView
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
 * app, the share sheet, another app, or a link. It shows the sentence, the terms of SubRead
 * Dictionary at the word, and a "+ Anki" button for each term. A tap on a character of the
 * text moves the word there. The card gets the term, the sentence around the word with the
 * word in bold, and the picture and the sound of the moment the text came in.
 *
 * The sentence comes from the sender, the line that the user selected on SubRead Overlay, or
 * the text of the view in another app ([TextService]). Under the sentence, the picture and
 * the sound of the card each have buttons: crop or remove the picture; play, record again or
 * remove the sound. A new recording takes the sound of the device between "Record" and
 * "Stop"; meanwhile the pop-up is a small bar, and the player behind it takes the touches.
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

    /** True while [player] plays the sound of the sentence, not the audio of a word. */
    private var playsSound = false
    private lateinit var root: View
    private lateinit var textView: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var pictureView: ImageView
    private lateinit var pictureButtons: List<View>
    private lateinit var noPicture: TextView
    private lateinit var soundButton: Button
    private lateinit var removeSound: Button
    private var popupHeight = 0

    /** When the recording started, on the clock of the capture ring; 0 while there is no recording. */
    private var recordFrom = 0L

    /** True when "Record" waits for the capture that Android asks the user for. */
    private var recordAfterCapture = false

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
        // The overlay answers with the line that the user selected, else with the line of now.
        val overlay = OverlayClient.now(this)
        val scan = Scans.resolve(parsed.expression, parsed.sentence, overlay?.line?.text, overlay?.selection, TextService.recent()) ?: run {
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
        show(scan)
        work.execute {
            val deck = runCatching { AnkiClient(this).deckName() }.getOrDefault(AnkiClient.DECK_NAME)
            runOnUiThread { if (!isFinishing) status.text = getString(R.string.deck_current, deck) }
        }
        showMedia()
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

    override fun onResume() {
        super.onResume()
        // Back from the question of Android for the capture: the recording starts when the
        // capture runs. When the user said no, or the capture is slow, "Record" starts it later.
        if (recordAfterCapture) window.decorView.postDelayed({
            if (recordAfterCapture) {
                recordAfterCapture = false
                if (CaptureService.instance != null) startRecording()
            }
        }, CAPTURE_WAIT_MS)
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
                val length = scan.length.takeIf { it > 0 } ?: word?.length ?: 0
                val card = if (request.definition != null) {
                    if (scan.offset >= 0 && length > 0) {
                        // The selection can be longer than the word of the dictionary: find the word in it, for the bold.
                        val range = Scans.wordIn(scan.text, scan.offset, length, word) { at ->
                            DictionaryClient.lookup(this, scan.text, at).firstOrNull { it.expression == word }?.length
                        }
                        Miner.card(request, scan.text, range.first, range.count(), null)
                    } else {
                        Miner.card(request, scan.text, -1, 0, null)
                    }
                } else if (scan.offset >= 0) {
                    val entry = DictionaryClient.lookup(this, scan.text, scan.offset).firstOrNull()
                    if (entry != null) {
                        Miner.card(request, scan.text, scan.offset, entry.length, entry)
                    } else {
                        val range = Miner.wordAt(scan.text, scan.offset, selected = isSelection(scan.text, scan.offset))
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
                finishWith(null)
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
        popupHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
        window.attributes = window.attributes.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            this.height = popupHeight
            gravity = Gravity.BOTTOM
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        textView = TextView(this).apply {
            textSize = TEXT_SP + 3
            setTextColor(Color.BLACK)
            maxLines = 5
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
            setPadding(dp(16), 0, dp(16), dp(4))
            text = "…"
        }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.END
            addView(slimButton(this@AddActivity, getString(R.string.settings)) { startActivity(Intent(this@AddActivity, MainActivity::class.java)) })
            addView(slimButton(this@AddActivity, getString(R.string.close)) { finish() })
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(textView, LinearLayout.LayoutParams(-1, -2))
            addView(media(), LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@AddActivity).apply { setBackgroundColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(ScrollView(this@AddActivity).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, popupHeight))
    }

    /** The picture and the sound of the card, each on a row with its buttons. */
    private fun media(): View {
        pictureView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = getString(R.string.crop)
            setOnClickListener { crop() }
        }
        noPicture = TextView(this).apply {
            text = getString(R.string.no_picture)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, dp(8), 0)
        }
        pictureButtons = listOf(
            slimButton(this, getString(R.string.crop)) { crop() },
            slimButton(this, getString(R.string.remove)) { removePicture() },
        )
        val pictureRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pictureView, LinearLayout.LayoutParams(-2, dp(PICTURE_DP)).apply { rightMargin = dp(8) })
            addView(noPicture)
            pictureButtons.forEach { addView(it) }
        }
        soundButton = slimButton(this, getString(R.string.no_sound)) { replay() }
        removeSound = slimButton(this, getString(R.string.remove)) { removeSound() }
        val soundRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(soundButton)
            addView(slimButton(this@AddActivity, getString(R.string.record)) { record() })
            addView(removeSound)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(pictureRow, LinearLayout.LayoutParams(-1, -2))
            addView(soundRow, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Shows the picture and the sound of the card as they are now. From any thread: it reads them on the work thread. */
    private fun showMedia() {
        // A callback that comes after the pop-up closed: the work thread stopped, and nothing shows.
        if (work.isShutdown) return
        work.execute {
            val grabbed = runCatching { grab?.get() }.getOrNull()
            val picture = grabbed?.picture
            val thumbnail = picture?.let { thumbnail(it) }
            val sound = grabbed?.sentenceAudio
            val ms = grabbed?.soundMs
            runOnUiThread {
                if (isFinishing || !::pictureView.isInitialized) return@runOnUiThread
                pictureView.setImageBitmap(thumbnail)
                pictureView.visibility = if (thumbnail != null) View.VISIBLE else View.GONE
                noPicture.visibility = if (thumbnail != null) View.GONE else View.VISIBLE
                noPicture.text = getString(if (CaptureService.instance == null && grabbed?.original == null) R.string.status_capture_off else R.string.no_picture)
                pictureButtons.forEach { it.isEnabled = grabbed?.original != null }
                pictureButtons.last().isEnabled = picture != null
                soundButton.isEnabled = sound != null
                removeSound.isEnabled = sound != null
                soundButton.text = when {
                    sound == null -> getString(R.string.no_sound)
                    playsSound -> getString(R.string.stop)
                    ms != null -> getString(R.string.sound_seconds, "%.1f".format(ms / 1000.0))
                    else -> getString(R.string.sound)
                }
            }
        }
    }

    /** A small copy of the picture for the pop-up: the picture itself can be the size of the screen. */
    private fun thumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= dp(PICTURE_DP)) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Opens the crop screen with the picture as it came, and the last crop of it. */
    private fun crop() {
        work.execute {
            val grabbed = runCatching { grab?.get() }.getOrNull() ?: return@execute
            val original = grabbed.original ?: return@execute
            val box = grabbed.cropBox
            runOnUiThread {
                val intent = Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PICTURE, original.path)
                if (box != null) intent.putExtra(CropActivity.EXTRA_BOX, box)
                startActivityForResult(intent, CROP)
            }
        }
    }

    @Deprecated("The platform Activity has no other result API, and this app has no AndroidX activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != CROP || resultCode != RESULT_OK || data == null) return
        val path = data.getStringExtra(CropActivity.EXTRA_PICTURE) ?: return
        val box = data.getFloatArrayExtra(CropActivity.EXTRA_BOX)
        work.execute {
            val grabbed = runCatching { grab?.get() }.getOrNull() ?: return@execute
            grabbed.picture = File(path)
            grabbed.cropBox = box
        }
        showMedia()
    }

    private fun removePicture() {
        work.execute { runCatching { grab?.get() }.getOrNull()?.picture = null }
        showMedia()
    }

    private fun removeSound() {
        stopPlayer()
        work.execute { runCatching { grab?.get() }.getOrNull()?.let { it.sentenceAudio = null; it.soundMs = null } }
        showMedia()
    }

    /** Plays the sound of the sentence; a second tap stops it. */
    private fun replay() {
        if (playsSound) {
            stopPlayer()
            showMedia()
            return
        }
        work.execute {
            val sound = runCatching { grab?.get() }.getOrNull()?.sentenceAudio
            runOnUiThread {
                if (sound == null || isFinishing || isDestroyed) return@runOnUiThread
                playFile(sound, sentence = true)
                showMedia()
            }
        }
    }

    /**
     * Starts a new recording of the sound of the sentence. The capture must run: without it,
     * Android first asks the user for it, and the recording starts when the capture runs.
     */
    private fun record() {
        if (CaptureService.instance == null) {
            recordAfterCapture = true
            toast(getString(R.string.record_capture))
            startActivity(Intent(this, CaptureActivity::class.java))
            return
        }
        startRecording()
    }

    /**
     * The pop-up becomes a small bar at the bottom with "Stop" and the seconds. Touches outside
     * the bar go to the app behind it, so that the user can seek back and play the player.
     */
    private fun startRecording() {
        stopPlayer()
        recordFrom = SystemClock.elapsedRealtimeNanos()
        val seconds = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(dp(16), 0, dp(8), 0)
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(seconds, LinearLayout.LayoutParams(0, -2, 1f))
            addView(slimButton(this@AddActivity, getString(R.string.stop)) { stopRecording() }.apply { setTypeface(typeface, Typeface.BOLD) })
        }
        setContentView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        window.attributes = window.attributes.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setFinishOnTouchOutside(false)
        val from = recordFrom
        val tick = object : Runnable {
            override fun run() {
                if (recordFrom != from) return
                val passed = ((SystemClock.elapsedRealtimeNanos() - from) / 1_000_000_000L).toInt()
                seconds.text = getString(R.string.recording, passed)
                // The ring keeps a little more than this: the start of the recording must still be in it.
                if (passed >= MAX_RECORD_S) stopRecording() else seconds.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    /** Ends the recording: its sound goes on the card, and the pop-up comes back. */
    private fun stopRecording() {
        val from = recordFrom
        if (from == 0L) return
        val to = SystemClock.elapsedRealtimeNanos()
        recordFrom = 0L
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, popupHeight))
        window.attributes = window.attributes.apply { height = popupHeight }
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setFinishOnTouchOutside(true)
        work.execute {
            val grabbed = runCatching { grab?.get() }.getOrNull() ?: return@execute
            val clip = CaptureService.instance?.clip(from, to, Media.file(this, "${grabbed.stem}_${System.currentTimeMillis()}.m4a"))
            if (clip == null) {
                runOnUiThread { toast(getString(R.string.record_silent)) }
            } else {
                grabbed.sentenceAudio = clip
                grabbed.soundMs = (to - from) / 1_000_000L
            }
        }
        showMedia()
    }

    private fun show(scan: Scan) {
        text = scan.text
        textView.text = text
        if (scan.offset >= 0) scanAt(scan.offset) else lookUpWord(scan.word.orEmpty())
    }

    /** Looks up the terms that start at [at] in the text. */
    private fun scanAt(at: Int) {
        val run = ++generation
        mark(null)
        thread {
            val found = DictionaryClient.lookup(this, text, at)
            runOnUiThread { if (run == generation && !isFinishing) showTerms(found, at) }
        }
    }

    /** The word of the sender is not in the text: its terms, with nothing marked. */
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
        val add = slimButton(this, getString(R.string.add_card)) {}.apply {
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
                if (entry?.audio != null) addView(slimButton(this@AddActivity, "▶") { play(entry) })
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

    /** Plays the audio of the word from the dictionary. */
    private fun play(entry: DictionaryClient.Entry) {
        val ask = entry.audio ?: return
        thread {
            val bytes = DictionaryClient.audio(this, ask)
            runOnUiThread {
                // The pop-up closed while the dictionary looked for the audio: nothing plays.
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bytes == null) {
                    toast(getString(R.string.no_audio))
                    return@runOnUiThread
                }
                playFile(File(cacheDir, "play.tmp").apply { writeBytes(bytes) }, sentence = false)
                showMedia()
            }
        }
    }

    /** Plays [file]. [sentence] is true for the sound of the sentence: its button then says "Stop". */
    private fun playFile(file: File, sentence: Boolean) {
        stopPlayer()
        player = runCatching {
            MediaPlayer().apply {
                FileInputStream(file).use { setDataSource(it.fd) }
                setOnCompletionListener {
                    playsSound = false
                    showMedia()
                }
                prepare()
                start()
            }
        }.getOrNull()
        playsSound = sentence && player != null
    }

    private fun stopPlayer() {
        player?.release()
        player = null
        playsSound = false
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PERMISSION = 1
        private const val CROP = 2

        /** The text size of the pop-up, in sp: the same as SubRead Dictionary. */
        private const val TEXT_SP = 17f

        /** The height of the picture in the pop-up, in dp. */
        private const val PICTURE_DP = 72

        /** A recording stops on its own after this many seconds: the ring of the capture keeps 90 s. */
        private const val MAX_RECORD_S = CaptureService.SECONDS - 5

        /** How long the pop-up waits for the capture to run after the user allowed it. */
        private const val CAPTURE_WAIT_MS = 800L
    }
}
