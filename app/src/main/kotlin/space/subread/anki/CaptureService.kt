package space.subread.anki

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.graphics.scale
import java.io.File

/**
 * What the capture service saw last: the text selection of another app, and a screenshot from
 * that moment. The card takes the sentence from the one and the image from the other.
 */
object Capture {

    /** A selection in an app: the whole text of the view, and the selected part of it. */
    data class Selection(val packageName: String, val text: String, val start: Int, val end: Int, val at: Long) {
        val word: String get() = text.substring(start, end)
    }

    data class Shot(val file: File, val packageName: String?, val at: Long)

    @Volatile
    var selection: Selection? = null

    @Volatile
    var shot: Shot? = null

    /** The app in front, the last one that was not this app. */
    @Volatile
    var front: String? = null

    /** True when the user turned the service on in the accessibility settings of Android. */
    fun isEnabled(context: Context): Boolean {
        val me = ComponentName(context, CaptureService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/**
 * The accessibility service. It listens to two kinds of events: a text selection changed, and a
 * window came to the front. After a selection it takes a screenshot, before a menu or a pop-up
 * can cover the app. It reads no other content and sends nothing anywhere.
 */
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val shootLater = Runnable { screenshot { } }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = event.packageName?.toString() ?: return
        if (app == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (app != AnkiDroid.PACKAGE && app != SYSTEM_UI) Capture.front = app
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> selected(app, event)
            else -> Unit
        }
    }

    private fun selected(app: String, event: AccessibilityEvent) {
        val node = event.source
        val text = node?.text?.toString() ?: event.text.joinToString("")
        val start = node?.textSelectionStart ?: event.fromIndex
        val end = node?.textSelectionEnd ?: event.toIndex
        if (start < 0 || end <= start || end > text.length) return
        Capture.selection = Capture.Selection(app, text, start, end, System.currentTimeMillis())
        // The screenshot waits a moment: the selection handles settle, and Android allows one shot in a third of a second.
        handler.removeCallbacks(shootLater)
        handler.postDelayed(shootLater, SHOT_DELAY_MS)
    }

    /**
     * Takes a screenshot and keeps it as the last shot. The callback gets the file, or null when
     * Android refuses: before Android 11, or a shot too soon after the last one. From the main thread.
     */
    fun screenshot(callback: (File?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return callback(null)
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    val file = bitmap?.let { save(it) }
                    if (file != null) {
                        Capture.shot?.file?.delete()
                        Capture.shot = Capture.Shot(file, Capture.front, System.currentTimeMillis())
                    }
                    callback(file)
                }

                override fun onFailure(errorCode: Int) = callback(null)
            },
        )
    }

    /** The screenshot as a JPEG in the cache, no side longer than [MAX_SIDE] pixels. */
    private fun save(bitmap: Bitmap): File? = runCatching {
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
        val file = Media.file(this, "shot", "jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        file
    }.getOrNull()

    companion object {
        const val SHOT_DELAY_MS = 400L
        const val MAX_SIDE = 1280
        const val JPEG_QUALITY = 85
        private const val SYSTEM_UI = "com.android.systemui"

        /** The service that Android runs now; null when the user did not turn it on. */
        @Volatile
        var instance: CaptureService? = null
            private set
    }
}
