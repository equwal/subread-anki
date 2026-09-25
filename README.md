# SubRead Anki

One tap makes an Anki card: the word, its reading and definition, the
sentence it is in, the sound of that sentence, and a picture of the screen.
The card goes into [AnkiDroid](https://github.com/ankidroid/Anki-Android)
on the device.

The tap can come from anywhere: a word of the subtitle line of
[SubRead Overlay](https://github.com/equwal/subread-overlay), the text
selection menu of any app (a reader, a browser, an OCR app), the share sheet,
another app, or a link on a web page. The app fills in what the tap did not
say: the definition from
[SubRead Dictionary](https://github.com/equwal/subread-dictionary), the
sentence and its times from the overlay, the sound and the picture from the
capture. It shows nothing of its own: a toast says "Added to Anki: 猫" and
the reader or the player stays in view.

Nothing leaves the device. The app talks to AnkiDroid, SubRead Overlay and
SubRead Dictionary on the device, and opens a URL only when a sender gives
one for an audio or a picture.

## How to use it

1. Install AnkiDroid and allow SubRead Anki to add cards to it. AnkiDroid
   must have "Enable AnkiDroid API" on, under Advanced in its settings; it
   is on by default.
2. Choose the deck. Choose the note type, or let the app make its own,
   "SubRead Anki". The app guesses what goes into each field from the field
   names: the [Lapis](https://github.com/donkuri/lapis) note type and the
   basic ones work as they are. "Fields" changes the guess.
3. Install SubRead Dictionary and import a dictionary into it, for the
   reading, the definition, the pitch accent, the frequency and the word
   audio.
4. Install SubRead Overlay, and choose "Anki" as its dictionary, or use its
   "Anki" button under a selected word. A tap on a word of the subtitle
   line then makes the card: the line is the sentence, and its times cut
   the sound.
5. Start the capture, from the app or from its tile in the quick settings.
   Android asks each time. While the capture is on, the app keeps the last
   minute and a half of the sound of the device and the last picture of the
   screen, and each card gets the sound of its line and the picture. Stop it
   from the notification or the tile.

In any other app: select a word and choose "Anki" in the text selection
menu. The sentence comes from the overlay when the word is in the line of
now. Select a whole sentence, and the card is a sentence card. Share a
picture to "Anki" within an hour of a card, and it goes onto that card.

Two options: "Ask before adding" shows the sentence and the terms of the
dictionary first, with one tap to add; off, one tap adds. "A word that is in
the deck already: skip" keeps a second tap from making a second card.

## What goes on the card

| Part | Where it comes from |
|---|---|
| Expression, reading, definition, pitch, frequency | SubRead Dictionary, for the longest term at the start of the selection. `食べた` gives the card `食べる`. A sender can give its own. |
| Sentence | The sender, else the subtitle line of now from SubRead Overlay when the selection is in it. The word is in `<b>` as it is in the sentence. |
| Sentence audio | The sender's file, cut to the times it gives. Else the capture, cut to the times of the subtitle line on the clock of the player, with a pad on each side. Without a line, the eight seconds before the tap. AAC in `.m4a`: every player and every Anki has it. |
| Word audio | The sender, else the audio of SubRead Dictionary (the local audio server, or a remote source when on). |
| Picture | The sender, else the newest picture of the screen from the capture. JPEG, at most 1600 pixels on the long side. |
| Source, tags | The sender. The tags of the settings go on every card. |

The note type "SubRead Anki" has the fields Expression, Reading, Definition,
Sentence, SentenceAudio, WordAudio, Image and Source, and one card: the word
and the sentence in front, the rest behind, black on white.

## For other apps

Four doors, all into one activity, `space.subread.anki.AddActivity`. It
shows nothing and closes when the card is added. Any part can be left out:
the app fills it in as the table above says.

**The text selection menu and the share sheet.** No code: the app has an
entry in both. `Intent.ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT`, or
`Intent.ACTION_SEND` with `text/plain` and `EXTRA_TEXT`. The text is the
word. `ACTION_SEND` with an `image/*` stream and a text is the picture and
the word; the picture alone goes onto the last card.

**An Intent from an app on the device.** The action
`space.subread.anki.action.ADD`, with the keys of the table below as extras,
each with the prefix `space.subread.anki.extra.` and in upper case:

```kotlin
val add = Intent("space.subread.anki.action.ADD").apply {
    setPackage("space.subread.anki")
    putExtra("space.subread.anki.extra.EXPRESSION", "食べた")
    putExtra("space.subread.anki.extra.SENTENCE", "猫が魚を食べた。")
    putExtra("space.subread.anki.extra.AUDIO", audioUri)        // Uri, or a String
    putExtra("space.subread.anki.extra.AUDIO_START", 96_000L)  // Long, or a String
    putExtra("space.subread.anki.extra.AUDIO_END", 99_500L)
    putExtra("space.subread.anki.extra.IMAGE", pageUri)
    putExtra("space.subread.anki.extra.SOURCE", "吾輩は猫である")
    clipData = ClipData.newRawUri("audio", audioUri).apply { addItem(ClipData.Item(pageUri)) }
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivityForResult(add, REQUEST_CARD)   // or startActivity
```

A `content://` Uri must be one the app may grant: its own `FileProvider`, or
a document Uri it holds a permission for. A `file://` Uri is refused. The
extras of the intent API of the `main` branch are taken too, with their
meaning there: `WORD`, `TEXT`, `AUDIO` (the word audio), `SENTENCE_AUDIO`,
`SHOW`. The "Anki" buttons of SubRead Overlay and SubRead Dictionary send
those. Declare the package in the manifest of the calling app, or Android 11
and later hide it:

```xml
<queries>
    <package android:name="space.subread.anki" />
</queries>
```

The result, for a caller that started the activity for one: `RESULT_OK` with
`space.subread.anki.extra.NOTE_ID` (`Long`, the note in AnkiDroid), or
`RESULT_CANCELED` with `space.subread.anki.extra.ERROR` (`String`): `empty`,
`no_ankidroid`, `no_permission`, `duplicate`, `cancelled`, or what AnkiDroid
said.

**A link on a web page.** The same keys, percent-encoded, in the query of
`subreadanki://add`. A web reader that has the sentence, the audiobook and
the times makes the card with one link:

```javascript
const query = new URLSearchParams({
  expression: "食べた",
  sentence: "猫が魚を食べた。",
  audio: "https://example.com/books/neko.m4b",   // the whole file: the app reads the range alone
  audio_start: "96000",
  audio_end: "99500",
  image: "https://example.com/books/neko/page-12.jpg",
  source: "吾輩は猫である",
}).toString();
// Chrome: an intent link, with a fallback for a phone without the app.
location.href = "intent://add?" + query + "#Intent;scheme=subreadanki;package=space.subread.anki;" +
  "S.browser_fallback_url=" + encodeURIComponent("https://github.com/equwal/subread-anki") + ";end";
// Firefox and the others: the plain link.
// location.href = "subreadanki://add?" + query;
```

The link must come from a tap of the user: browsers block an app link
without one. An `http(s)` audio is read with range requests, so a whole
audiobook is fine; a picture is fetched whole. A `data:` Uri works for a
small file. The app shows a toast and goes back to the browser.

**The keys.** Each is optional; a card needs `expression` or `sentence`.

| Key | Value |
|---|---|
| `expression` | The word, as it is in the text. |
| `reading` | The reading of the word. |
| `definition` | The definition, HTML or text. Given, the dictionary is not asked. |
| `sentence` | The sentence, plain text. |
| `audio` | A Uri of the audio of the sentence: `content://`, `https://`, `http://` or `data:`. |
| `audio_start`, `audio_end` | With `audio`: the clip, in milliseconds of the audio. Without them, the whole file is the clip. |
| `word_audio` | A Uri of the audio of the word alone. |
| `image` | A Uri of the picture. |
| `source` | Where the sentence is from: the title of the book or the video. |
| `tags` | Anki tags, separated by spaces. |
| `pitch`, `frequency` | Text for the pitch and frequency fields. |
| `position` | The position of the player at the tap, in milliseconds. With the capture on and no `audio`, the clip is cut around it. |
| `cue_start`, `cue_end` | The subtitle line, in milliseconds on the clock of the player. With the capture on and no `audio`, the clip is this range. |
| `confirm` | `1`: show the card before it is added, whatever the setting says. `0`: add at once. |

## How it works

`:core` is plain Kotlin, with no Android in it, and has property tests: the
request and its query, the sentence around a word, the clock of a player
report, the ring of captured sound, the fields of a note type. `:app` has
the activity, the capture service, the AnkiDroid client and the two content
provider clients.

The sound of the capture is 16-bit PCM in a ring with a clock. A player
reports a position, the time of that report and the speed; the app maps the
times of the subtitle line onto the clock of the ring and reads that range
out. A pause reported a minute ago still maps its media times to the right
moments. The clip is encoded with the AAC encoder of Android.

SubRead Overlay answers `content://space.subread.overlay.player/line` with
the line of now and the report of the player. SubRead Dictionary answers
`content://space.subread.dictionary.lookup/terms?text=…` with the terms,
and gives the word audio through the same provider. Their READMEs have the
details.

## Build

```
./gradlew :core:test :app:assembleDebug
```

`-PplayStore=true` leaves the Ko-fi link out of the build for Google Play.

## Licence

AGPL-3.0. See `LICENSE`.
