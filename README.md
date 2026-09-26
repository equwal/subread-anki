# SubRead Anki

One tap makes an Anki card on Android: the word, its reading and its definition, the
sentence the word is in, a screenshot of the screen, the word audio and the sentence audio.
The card goes into [AnkiDroid](https://github.com/ankidroid/Anki-Android) on the device.
Nothing leaves the device.

It works with any app:

- Select a word in any app and choose **"Add to Anki"** in the text selection menu.
- Share a text to SubRead Anki. A single word becomes the card; in a longer text you tap
  the word.
- Tap **"Anki"** in the pop-up of [SubRead Dictionary](https://github.com/equwal/subread-dictionary),
  or under a selected word in [SubRead Overlay](https://github.com/equwal/subread-overlay).
- Any other app can send a card with one intent, see [docs/intent-api.md](docs/intent-api.md).

## What goes on the card

| Part | Where it comes from |
|---|---|
| Word, reading, definition | The app that sends the card, or else SubRead Dictionary, which reads Yomitan dictionaries (JMdict, Jitendex). The word goes on the card in its dictionary form. |
| Sentence | The app that sends the card, or else the text around the word that you selected, when the capture service is on. The word is in bold. |
| Screenshot | The capture service takes it at the moment you select the word, before a menu or a pop-up covers the app. |
| Word audio | The app that sends the card, or else the first audio source of SubRead Dictionary (the local audio server file, or a remote source). |
| Sentence audio | The voice of the device reads the sentence. Japanese text gets the Japanese voice. |
| Source | The name of the app or the media the sentence is from. |

The default note type "SubRead" has the fields Word, Reading, Definition, Sentence, Image,
Audio, SentenceAudio and Source. The app makes it, and a "SubRead" deck, on the first card.
Any other note type works: the settings map each field to a part of the card, with a guess
from the field names. The word can also go into a field as `食[た]べる` for the furigana
filter of Anki, or as a cloze.

## How to use it

1. Install AnkiDroid and open it once, so that it has a collection.
2. Open SubRead Anki and allow it to write to the AnkiDroid database.
3. Turn on the capture service in the accessibility settings of Android, for the sentence
   and the screenshot. This step is optional. See [Privacy](#privacy).
4. Install SubRead Dictionary and import a dictionary, for the definition, the reading and
   the word audio. This step is optional: without it, the card has the word, the sentence,
   the screenshot and the sentence audio, and you can type a definition.
5. Select a word anywhere and choose "Add to Anki". A toast says "Added to Anki: 食べる".

A card that misses its definition, or a text with no word chosen, shows as a sheet first:
you complete it and tap "Add to Anki". The setting "Show the card first" makes every card
show first.

## Privacy

The capture service is an accessibility service. Android gives it the text of the view in
which you select a word, and the right to take a screenshot. The service keeps the last
selection and the last screenshot in the cache of the app for the next card, and nothing
else. It sends nothing anywhere. It is off until you turn it on, and the app works without
it.

The card goes to AnkiDroid on the same device. The app has no network permission.

## Layout

| Path | What |
|---|---|
| `core/` | Plain Kotlin: the sentence cut, the furigana, the fields of a card. Tests run with a JDK alone. |
| `app/` | The Android app: the card sheet, the settings, the capture service, the AnkiDroid client, the voice. |
| `docs/intent-api.md` | The intent that another app sends to add a card. |

## Build

```
./gradlew :core:test :app:assembleDebug
```

The release build signs with the key in `SUBREAD_KEYSTORE_FILE` and `SUBREAD_KEYSTORE_PASSWORD`.
`-PplayStore=true` leaves the Ko-fi link out.

## More projects

- [SubRead](https://subread.space/): read along with an audiobook, in the browser.
  Also [for Android](https://github.com/equwal/subread-android/releases/latest),
  [for YouTube](https://github.com/equwal/subread-extension/releases/latest)
  and [for KOReader](https://github.com/equwal/subread.koplugin).
- [SubRead Overlay](https://github.com/equwal/subread-overlay/releases/latest): subtitle lines over any Android media player.
- [SubRead Dictionary](https://github.com/equwal/subread-dictionary/releases/latest): a pop-up dictionary for Android that reads Yomitan dictionaries.
- [Subrep](https://github.com/equwal/subrep-android/releases/latest): live captions of the sound of your phone.
- [Book Simulator](https://booksimulator.com/): a reading room for Aozora Bunko and Project Gutenberg books.
- [honjimaku.com](https://honjimaku.com/): subtitles for Japanese audiobooks.
- [sbm Sync](https://sbmsync.com/): your bookmarks, the same on every device,
  with [sbm](https://github.com/equwal/sbm) for dmenu,
  [sbm for Android](https://github.com/equwal/sbm-android/releases/latest)
  and the [sbm add-on](https://github.com/equwal/sbm-extension/releases/latest) for Firefox and Chrome.
- [Rebind](https://github.com/equwal/rebind/releases): remap the hardware buttons of e-ink readers and Android,
  with [Ink Recents](https://github.com/equwal/ink-recents/releases/latest),
  [Ink Dim](https://github.com/equwal/ink-dim/releases/latest)
  and [Ink Update](https://github.com/equwal/ink-update/releases/latest).
- [dickt.store](https://dickt.store/): language-learning tools, flashcards and web toys.
- [hentaibun.online](https://hentaibun.online/): learn kanbun and kobun.
- [Recently Written](https://recentlywritten.com/): the blog, and a list of [all projects](https://recentlywritten.com/projects.html).

## License

AGPL-3.0-only. The AnkiDroid content provider contract (the authority, the paths and the
columns) is used as data; the API library of AnkiDroid is not included.
