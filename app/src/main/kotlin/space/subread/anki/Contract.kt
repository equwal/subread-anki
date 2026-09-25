package space.subread.anki

/**
 * The intent that another app sends to add a card: SubRead Dictionary, SubRead Overlay, a
 * reader. The strings are the contract, see docs/intent-api.md. Another app copies them; it
 * needs no code of this app.
 */
object Contract {
    const val PACKAGE = "space.subread.anki"
    const val ACTION_ADD = "space.subread.anki.action.ADD"

    /** The word, in its dictionary form when the sender knows it. */
    const val EXTRA_WORD = "space.subread.anki.extra.WORD"
    const val EXTRA_READING = "space.subread.anki.extra.READING"
    /** The definition as simple HTML. */
    const val EXTRA_DEFINITION = "space.subread.anki.extra.DEFINITION"
    /** The sentence the word is in, as plain text. */
    const val EXTRA_SENTENCE = "space.subread.anki.extra.SENTENCE"
    /** A text with no word chosen yet: the user taps the word in it. */
    const val EXTRA_TEXT = "space.subread.anki.extra.TEXT"
    /** Content URIs, as strings. The sender grants the read permission through the clip data of the intent. */
    const val EXTRA_IMAGE = "space.subread.anki.extra.IMAGE"
    const val EXTRA_AUDIO = "space.subread.anki.extra.AUDIO"
    const val EXTRA_SENTENCE_AUDIO = "space.subread.anki.extra.SENTENCE_AUDIO"
    /** Where the sentence is from: a book, a video, an app. */
    const val EXTRA_SOURCE = "space.subread.anki.extra.SOURCE"
    const val EXTRA_URL = "space.subread.anki.extra.URL"
    /** Tags for the note, separated by spaces, added to the tags of the settings. */
    const val EXTRA_TAGS = "space.subread.anki.extra.TAGS"
    /** True: take a screenshot now when the capture service is on and no image came. */
    const val EXTRA_SCREENSHOT = "space.subread.anki.extra.SCREENSHOT"
    /** True: show the card before it is added, also when the settings say to add at once. */
    const val EXTRA_SHOW = "space.subread.anki.extra.SHOW"
    /** In the result of the activity: the id of the note in Anki. */
    const val EXTRA_NOTE_ID = "space.subread.anki.extra.NOTE_ID"
}
