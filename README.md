# SubRead Anki

A pop-up for Anki cards, used the same way as a dictionary. Select a text
in any app (a reader, a browser, an OCR app) and choose "Anki card" in the
text selection menu. The pop-up opens over the app and shows the text and
the terms at the word, from
[SubRead Dictionary](https://github.com/equwal/subread-dictionary). A tap on
a character of the text moves the word there. Tap "＋ Anki" on a term: the
card goes into [AnkiDroid](https://github.com/ankidroid/Anki-Android) with
the word, its reading and definition, the sentence around the word, a
picture of the screen and the sound of the sentence.

The app is on its own: it needs no other SubRead app. With SubRead
Dictionary, the pop-up has the definitions. With
[SubRead Overlay](https://github.com/equwal/subread-overlay), a subtitle
word shows its whole line, and the card gets the sound of that line.

Nothing leaves the device. The app talks to AnkiDroid and SubRead
Dictionary on the device, and opens a URL only when a sender gives one for
an audio or a picture.

## How to use it

1. Install AnkiDroid and allow SubRead Anki to add cards to it. AnkiDroid
   must have "Enable AnkiDroid API" on, under Advanced in its settings; it
   is on by default.
2. Choose the deck. Choose the note type, or let the app make its own,
   "SubRead Anki". The app guesses what goes into each field from the field
   names: the [Lapis](https://github.com/donkuri/lapis) note type and the
   basic ones work as they are. "Fields" changes the guess.
3. Install SubRead Dictionary and import a dictionary into it, for the
   reading, the definition and the word audio.
4. For a picture and sound on the cards, start the capture, from the app or
   from its tile in the quick settings. Android asks each time. While the
   capture is on, the app keeps the last minute and a half of the sound of
   the device and the newest picture of the screen. Stop it from the
   notification or the tile.

Then, in any app: select a sentence, choose "Anki card", tap the word in the
pop-up, and tap "＋ Anki". A single selected word works too; the card then
has no sentence, unless the word is in the subtitle line of SubRead Overlay.
The button says "✓ Added", and a term that is in the deck already says
"✓ In Anki". A tap outside the pop-up closes it.

The line under the text says what the card gets: "Picture ✓   Sound 5.3 s ✓
  Deck: SubRead". The picture is the screen at the moment the text was
selected, from before the pop-up opened. The sound is the last eight
seconds before that moment: the audiobook that plays while you read. A clip
of silence is left out.

Share a picture to "Anki card" within an hour of a card, and it goes onto
that card. The option "add the first term at once, with no pop-up" makes a
selection a card with no pop-up.

## What goes on the card

| Part | Where it comes from |
|---|---|
| Expression, reading, definition | The term that you tap "＋ Anki" on, from SubRead Dictionary. `食べた` gives the card `食べる`. A sender can give its own. |
| Sentence | The sentence around the word in the text of the pop-up. The word is in `<b>` as it is in the sentence. |
| Sentence audio | The sender's file, cut to the times it gives. Else the capture: the subtitle line of SubRead Overlay, cut on the clock of the player with a pad on each side, or the eight seconds before the text came in. AAC in `.m4a`: every player and every Anki has it. |
| Word audio | The sender, else the audio of SubRead Dictionary (the local audio server, or a remote source when on). |
| Picture | The sender, else the screen from the capture at the moment the text came in. JPEG, at most 1600 pixels on the long side. |
| Source, tags | The sender, else the name of the app that sent the text. The tags of the settings go on every card. |

The note type "SubRead Anki" has the fields Expression, Reading, Definition,
Sentence, SentenceAudio, WordAudio, Image and Source, and one card: the word
and the sentence in front, the rest behind, black on white.

## For other apps

Four doors, all into one activity, `space.subread.anki.AddActivity`. Any
part can be left out: the app fills it in as the table above says. A
request with a `definition` is a whole card: it goes to AnkiDroid at once,
with no pop-up, and a toast says so. A request without one opens the
pop-up at the word, so the user picks the term. `confirm` changes that.

**The text selection menu and the share sheet.** No code: the app has an
entry in both. `Intent.ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT`, or
`Intent.ACTION_SEND` with `text/plain` and `EXTRA_TEXT`. The text is the
text of the pop-up. `ACTION_SEND` with an `image/*` stream and a text is the
picture and the text; the picture alone goes onto the last card.

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
small file. With a `definition`, the app shows a toast and goes back to the
browser; without one, the pop-up opens over the browser.

**The keys.** Each is optional; a card needs `expression` or `sentence`.

| Key | Value |
|---|---|
| `expression` | The word, as it is in the text. The pop-up opens at it. |
| `reading` | The reading of the word. |
| `definition` | The definition, HTML or text. Given, the card is added at once and the dictionary is not asked. |
| `sentence` | The sentence, plain text: the text of the pop-up. |
| `audio` | A Uri of the audio of the sentence: `content://`, `https://`, `http://` or `data:`. |
| `audio_start`, `audio_end` | With `audio`: the clip, in milliseconds of the audio. Without them, the whole file is the clip. |
| `word_audio` | A Uri of the audio of the word alone. |
| `image` | A Uri of the picture. |
| `source` | Where the sentence is from: the title of the book or the video. |
| `tags` | Anki tags, separated by spaces. |
| `pitch`, `frequency` | Text for the pitch and frequency fields. |
| `position` | The position of the player at the tap, in milliseconds. With the capture on and no `audio`, the clip is cut around it. |
| `cue_start`, `cue_end` | The subtitle line, in milliseconds on the clock of the player. With the capture on and no `audio`, the clip is this range. |
| `confirm` | `1`: open the pop-up, also with a definition. `0`: add at once, with the first term of the dictionary when there is no definition. |

## How it works

`:core` is plain Kotlin, with no Android in it, and has property tests: the
request and its query, the text of the pop-up and the sentence around a
word, the clock of a player report, the ring of captured sound and its
loudness, the fields of a note type. `:app` has the pop-up, the capture
service, the AnkiDroid client and the two content provider clients.

The pop-up takes the picture when the text comes in, before its own window
draws, and cuts the sound at once too. Each "＋ Anki" of the same pop-up
uses the same picture and sound.

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
