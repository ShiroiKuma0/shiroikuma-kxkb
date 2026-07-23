<div align="center">

<img src="app/src/main/ic_launcher-playstore.png" width="120" alt="白い熊 kxkb app icon" />

# 白い熊 kxkb

**An offline, privacy-respecting keyboard — supercharged for power users.**

A true-**FLOSS** fork of [Urik](https://github.com/urikdev/Urik) (GPL-3.0) with **major additions**:
fully offline Whisper voice input, cluster-word prediction in any language, a per-geometry
look-and-theme system, live on-keyboard resizing, an instant spacebar switcher, a curated
multi-layout roster (QWERTY/QWERTZ/ЯВЕРТЫ + cluster and compass layouts), and a FUTO-style
candidate line.

Installs **side-by-side** with the official Urik and with any other keyboard (package
`shiroikuma.kxkb`).

**📥 Latest release: [`0.23.1+296`](https://github.com/ShiroiKuma0/shiroikuma-kxkb/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-kxkb/releases)

</div>

---

## 🔮 Cluster-word prediction — in any language

Type whole words on **multi-letter cluster keys** and let the keyboard figure out the word you
meant. A constrained walk over Urik’s `.urik` dictionary trie, with **accent-folding**, makes
prediction work even for languages with **no neural model and lots of diacritics** (Czech, Russian,
…). Each tap is ambiguous over a small band of letters; the keyboard enumerates every dictionary
word consistent with what you tapped and ranks them by frequency.

- **Space commits** the highlighted candidate — both the word you’re typing *and* the next-word
  (bigram) prediction.
- **Tab cycles** the highlight across the candidates the bar is showing; **long-press Space**
  inserts a literal space when you don’t want the prediction.
- A **▾ expandable pane** opens a scrollable grid of the full long tail (tens of candidates).

---

## 🎙 Offline voice input — Whisper on the mic key

Dictate in any keyboard language with **OpenAI's Whisper running entirely on the phone** (the
[whisperIMEplus](https://github.com/woheller69/whisperIMEplus) ONNX engine). The keyboard has **no
internet permission** — nothing you type or say can leave the device — so a guided settings page
walks the one-time setup: your browser downloads the model, the page imports it, done.

- **Tap the mic** (it replaced the right Shift) to dictate; **continuous mode** commits sentence by
  sentence at your natural pauses while the mic keeps listening — recognition runs in parallel, so
  nothing said during a decode is lost. Beeps confirm each committed sentence and the session end.
- **Dictation follows the keyboard language**; **long-press the mic** to quick-flip to the pair's
  other language (Czech ⇄ English and friends), announced right in the candidate line.
- The model loads on first press (overlapping your speaking) and unloads after a minute idle.

---

## ⌨️ New kinds of keys, and many layouts

Far more than one letter per button, and far more than two layouts per language:

- **Cluster keys** pack several letters into one key.
- **Compass / 8-way keys** flick in eight directions for extra characters — including a full
  **GNU** compass layout and Emacs-style modifier chords.
- **Column** layouts for compact one-letter-per-key typing.
- A **layout registry** designed for *many* layouts per language (cs / en / ru / ja / gnu), with
  **width variants** grouped by family and per-language active-layout memory.
- **Secondary characters** ride on the keys (top row, bottom row, the flick sides), each with its
  own font, weight, size, colour and spacing.

---

## 👆 Glide typing on the flat boards — and tap-to-fix

The 10/12-column boards type by **swiping** — accent-aware (swipe `dobry`, get **dobrý**), scoped
to the current language, tuned for the compact kxkb arrangements. And fixing what's already written
is one tap: **tap any word** for correction candidates in the bar, or hit the **✎ chip** to edit it
letter-by-letter in an in-keyboard editor with a real caret — no prediction interference — then
commit it back over the word. Long-press any candidate to edit or ban it, and browse everything the
keyboard has learned in the per-language **Learned words** page (registered words wear a ＋ pill).

---

## 🎨 Per-geometry look — theme every pixel

A bold **black-and-yellow, square-key** high-contrast look ships as the default. Everything is
adjustable and **saved per geometry** (portrait / landscape / folded-inner / folded-outer):

- Recolour the **keyboard, keys, key text, borders, the caps-lock Shift glyph and the suggestion
  bar** with a full **RGBA picker**.
- Choose fonts — **system, monospace or your own imported font files** — via a glyph picker.
- Tune **key height, width, corner radius, border width, label weight, font scale, horizontal /
  vertical key spacing** and the secondary-character formatting.
- A **logical settings page** (Geometry / Keyboard / Keys / Rows / Compass keys / Cluster keys /
  Suggestion bar) instead of a flat list.

---

## 📐 Resize and reshape it live

Drag hot-points **on the keyboard itself** — height, bottom-lift, width — and watch it change
**underneath your finger** in real time. Every change persists for that geometry. A see-through
bottom-lift lets the app show through the gap below the keys.

---

## 🌀 One swipe to switch

**Slide the spacebar in any direction** for an instant 3-column menu: quick **actions**
(open the kxkb settings, the languages list, all settings, the system keyboard chooser), your other
**active languages**, and the **layouts** available in the current language — release on an item to
pick it, release outside to dismiss.

---

## ✍️ Power-user touches

- **No three-language cap** — Czech, English, Russian and Japanese coexist, all active at once.
- **Per-app layout-language memory** — each app reopens in the language you last used there.
- **Code / no-prediction field mode** — auto-detected raw-input fields turn off prediction and
  auto-caps, plus a manual toggle.
- **A real `Tab` key** that emits `KEYCODE_TAB` for shell / editor completion.
- **Punctuation auto-spacing** — `word ` + `.` becomes `word. ` (eat the space, attach the mark,
  re-space) for `.` `,` `:` `;` `!` `?`.
- The spacebar shows the **current language’s native name** (English, 日本語, GNU, …).
- **App interface language** setting, independent of the phone locale.

---

## Built on Urik

This project is a fork of [Urik](https://github.com/urikdev/Urik) (package `shiroikuma.kxkb`, so it
coexists with the official build). Urik is a privacy-focused, 100%-on-device Android keyboard with
swipe typing, an encrypted learned-word store, the `.urik` dictionary format, themes and adaptive
layouts. All upstream work and the project’s mission belong to the Urik team — see the
[upstream repository](https://github.com/urikdev/Urik) for issues, contributing and the canonical
source. The code remains under the **[GNU GPL v3](LICENSE)**.

The fork tracks upstream: `main` follows the latest Urik release tag, and all of our work lives on
the `custom` branch (rebased onto each new upstream release). The installed version is
`<urik-version>+<our-build>` (e.g. `0.23.1+72`).

## Building

```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-kxkb.git
cd shiroikuma-kxkb
export JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug      # fast debug build
./gradlew buildApk                # signed release build
```

Requirements: JDK 21, Android SDK 36, minSdk 26 (Android 8.0+). No NDK.

## Changelog

See **[CHANGELOG.md](CHANGELOG.md)** for the full, specific list of everything this fork adds on top
of stock Urik.
