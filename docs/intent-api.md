# The intent API of SubRead Anki

Another app makes a card with one intent. SubRead Anki fills in what the app does not
send: the definition and the audio from SubRead Dictionary, the sentence and the screenshot
from its capture service, the sentence audio from the voice of the device. Then it puts the
card in AnkiDroid, or shows it first when a part is missing or the user wants to see it.

## The intent

```kotlin
val intent = Intent("space.subread.anki.action.ADD")
    .setPackage("space.subread.anki")
    .putExtra("space.subread.anki.extra.WORD", "食べる")
    .putExtra("space.subread.anki.extra.READING", "たべる")
    .putExtra("space.subread.anki.extra.DEFINITION", "<i>Jitendex</i><br>to eat")
    .putExtra("space.subread.anki.extra.SENTENCE", "パンを食べた。")
    .putExtra("space.subread.anki.extra.SOURCE", "Voice: 走れメロス")
    .putExtra("space.subread.anki.extra.SCREENSHOT", true)
startActivity(intent)
```

On Android 11 and later, the manifest of the sender needs the package in its queries:

```xml
<queries>
    <package android:name="space.subread.anki" />
</queries>
```

## The extras

All extras are strings unless said otherwise. Every extra is optional, but the intent needs
`WORD` or `TEXT`.

| Extra | What |
|---|---|
| `space.subread.anki.extra.WORD` | The word, in its dictionary form when the sender knows it. SubRead Anki looks it up in SubRead Dictionary when `DEFINITION` is empty, and then uses the dictionary form. |
| `space.subread.anki.extra.READING` | The reading, in kana. |
| `space.subread.anki.extra.DEFINITION` | The definition, as simple HTML: `b`, `i`, `u`, `br`, `ul`, `li`, `a`. |
| `space.subread.anki.extra.SENTENCE` | The sentence the word is in, as plain text. The word goes in bold when it is in the sentence as it is, or as `WORD`. |
| `space.subread.anki.extra.TEXT` | A text with no word chosen. The card shows as a sheet, and the user taps the word in the text. |
| `space.subread.anki.extra.IMAGE` | A content URI of an image file, as a string. See [Files](#files). |
| `space.subread.anki.extra.AUDIO` | A content URI of the word audio, as a string. |
| `space.subread.anki.extra.SENTENCE_AUDIO` | A content URI of the sentence audio, as a string. |
| `space.subread.anki.extra.SOURCE` | Where the sentence is from: a book, a video, an app. Without it, the name of the sending app. |
| `space.subread.anki.extra.URL` | A link back to the source. |
| `space.subread.anki.extra.TAGS` | Tags for the note, separated by spaces. The tags of the settings are added to them. |
| `space.subread.anki.extra.SCREENSHOT` | Boolean. True: when no image came and the capture service saw no selection in the last 90 seconds, take a screenshot now. The sender hides its own window for about a second before it sends the intent, so that the screenshot shows the app under it. |
| `space.subread.anki.extra.SHOW` | Boolean. True: show the card before it goes to Anki, also when the settings say to add at once. |

## Files

An image or an audio file goes through a content URI, from a `FileProvider` of the sender.
The URI string goes in the extra; the read permission goes through the clip data of the
intent:

```kotlin
val uri = FileProvider.getUriForFile(context, "$packageName.files", file)
intent.putExtra("space.subread.anki.extra.AUDIO", uri.toString())
intent.clipData = ClipData.newUri(contentResolver, "audio", uri)
intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
```

With more than one file, add the other URIs as items of the same clip data. SubRead Anki
copies each file at once, so the sender can delete it after the activity returns.

## The result

The activity can be started for a result. `RESULT_OK` comes with the long extra
`space.subread.anki.extra.NOTE_ID`, the id of the note in Anki. The user can also close the
sheet: then the result is `RESULT_CANCELED`.

## The text selection menu and the share sheet

Without any code, an app that has selectable text gets "Add to Anki" in its text selection
menu (`ACTION_PROCESS_TEXT`, `text/plain`), and an app that shares text reaches SubRead Anki
in the share sheet (`ACTION_SEND`, `text/plain`, also with an image). A short text with no
space is the word; a longer one is a text in which the user taps the word.
