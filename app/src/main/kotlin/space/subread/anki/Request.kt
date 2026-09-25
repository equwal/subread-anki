package space.subread.anki

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri

/** What an intent asks for: the parts of the card that the sender knows. */
data class Request(
    val word: String = "",
    val reading: String = "",
    val definition: String = "",
    val sentence: String = "",
    val text: String = "",
    val image: Uri? = null,
    val audio: Uri? = null,
    val sentenceAudio: Uri? = null,
    val source: String = "",
    val url: String = "",
    val tags: String = "",
    /** True: take a screenshot now when no image came and none is fresh. */
    val screenshot: Boolean = false,
    /** True: show the card before it goes to Anki. */
    val show: Boolean = false,
    /** The package of the app that sent the intent, when Android says. */
    val caller: String? = null,
) {
    companion object {
        /** A selected or shared text this short, with no space and no end mark, is the word itself. */
        private const val WORD_MAX = 30
        private const val NOT_IN_A_WORD = " \t\n。．！？!?、,.;:"

        /** The request of the intent, or null when it brings nothing to make a card from. */
        fun of(intent: Intent, caller: String?): Request? = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null else fromText(text, null, caller)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val image = if (intent.type?.startsWith("image/") == true) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
                if (text.isEmpty()) null else fromText(text, image, caller)
            }
            Contract.ACTION_ADD -> {
                fun text(name: String) = intent.getStringExtra(name)?.trim().orEmpty()
                fun uri(name: String) = intent.getStringExtra(name)?.takeIf { it.isNotBlank() }?.toUri()
                Request(
                    word = text(Contract.EXTRA_WORD),
                    reading = text(Contract.EXTRA_READING),
                    definition = text(Contract.EXTRA_DEFINITION),
                    sentence = text(Contract.EXTRA_SENTENCE),
                    text = text(Contract.EXTRA_TEXT),
                    image = uri(Contract.EXTRA_IMAGE),
                    audio = uri(Contract.EXTRA_AUDIO),
                    sentenceAudio = uri(Contract.EXTRA_SENTENCE_AUDIO),
                    source = text(Contract.EXTRA_SOURCE),
                    url = text(Contract.EXTRA_URL),
                    tags = text(Contract.EXTRA_TAGS),
                    screenshot = intent.getBooleanExtra(Contract.EXTRA_SCREENSHOT, false),
                    show = intent.getBooleanExtra(Contract.EXTRA_SHOW, false),
                    caller = caller,
                ).takeIf { it.word.isNotEmpty() || it.text.isNotEmpty() }
            }
            else -> null
        }

        /** A short text with no space is the word. A longer one is a text: the user taps the word in it. */
        private fun fromText(text: String, image: Uri?, caller: String?): Request {
            val isWord = text.length <= WORD_MAX && text.none { it in NOT_IN_A_WORD }
            return Request(
                word = if (isWord) text else "",
                text = if (isWord) "" else text,
                image = image,
                screenshot = true,
                caller = caller,
            )
        }
    }
}
