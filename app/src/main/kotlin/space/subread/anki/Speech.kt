package space.subread.anki

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The voice of the device reads a sentence into a file. */
class Speech(private val context: Context) {

    /**
     * Writes the sentence as a wave file. Blocks until the voice is done: call it off the main
     * thread. False when the device has no voice for the language, or the voice fails.
     */
    fun synthesize(text: String, file: File, japanese: Boolean, timeoutMs: Long = 20_000): Boolean {
        val ready = CountDownLatch(1)
        var started = false
        val tts = TextToSpeech(context) { status ->
            started = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        try {
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS) || !started) return false
            val language = tts.setLanguage(if (japanese) Locale.JAPANESE else Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) return false

            val done = CountDownLatch(1)
            var success = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    success = true
                    done.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = done.countDown()

                override fun onError(utteranceId: String?, errorCode: Int) = done.countDown()
            })
            val queued = tts.synthesizeToFile(text, Bundle(), file, "subread-${System.nanoTime()}")
            if (queued != TextToSpeech.SUCCESS || !done.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
            return success && file.length() > 0
        } finally {
            tts.shutdown()
        }
    }
}
