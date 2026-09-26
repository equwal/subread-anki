package space.subread.anki

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import space.subread.anki.core.Around

/**
 * The accessibility service for the sentence. It gets one kind of event: a text selection
 * changed in another app. It keeps the newest one, in memory only: the text of the view, the
 * selected range, the app, and the time. The pop-up cuts the sentence around the word from
 * it. It reads no other content, takes no picture, and sends nothing anywhere.
 *
 * Off until the user turns it on in the accessibility settings of Android.
 */
class TextService : AccessibilityService() {

    /** A selection in another app: the whole text of the view, and the selected range of it. */
    data class Seen(
        val packageName: String,
        val text: String,
        val start: Int,
        val end: Int,
        /** When the selection changed, `SystemClock.elapsedRealtime()`. */
        val atMs: Long,
    )

    override fun onUnbind(intent: Intent?): Boolean {
        latest = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        latest = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        val app = event.packageName?.toString() ?: return
        if (app == packageName) return
        // The node has the whole text of the view, also of a text that the user cannot edit.
        // The event alone has the text of an edit field.
        val node = event.source
        if (node?.isPassword == true || event.isPassword) return
        val text = node?.text?.toString() ?: event.text.joinToString("")
        val start = node?.textSelectionStart ?: event.fromIndex
        val end = node?.textSelectionEnd ?: event.toIndex
        // A caret with no selection keeps the last selection: the user can tap "Anki card" after it.
        if (start < 0 || end <= start || end > text.length) return
        latest = Seen(app, text, start, end, SystemClock.elapsedRealtime())
    }

    companion object {
        /** The newest selection, or null. Only in memory: it is gone when the service or the app stops. */
        @Volatile
        var latest: Seen? = null
            private set

        /** A selection counts for this long: after that, it is from another moment than the request. */
        const val FRESH_MS = 15_000L

        /** The newest selection as a text around it, when it is at most [FRESH_MS] old; else null. */
        fun recent(): Around? = latest
            ?.takeIf { SystemClock.elapsedRealtime() - it.atMs <= FRESH_MS }
            ?.let { Around(it.text, it.start, it.end) }

        /** True when the user turned the service on in the accessibility settings of Android. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, TextService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
