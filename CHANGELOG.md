# Changelog

Everything **白い熊 kxkb** adds on top of stock [Urik](https://github.com/urikdev/Urik). The fork’s
version is `<urik-version>+<our-build-number>`; the build number increments on every release and
resets to 1 on each new upstream Urik version.

## 0.23.1+309 — current

Built on Urik `0.23.1-beta`. Punctuation, brackets and dashes typed **inside quotation marks** now
space themselves the way words already did.

### 🔤 A mark inside quotes no longer strands its space

- Typing `,` or `.` inside „…“ / “…” / (…) pushed a space in front of the closing mark — „Ahoj, “
  instead of „Ahoj,“. Words learned this rule long ago: a commit that lands before a closer holds
  its space back and hands it to the next word. Every mark now does the same, so the space appears
  only when the following word starts — „Ahoj, světe“.
- Applies to `.,?!:;`, the ellipsis, closing brackets and quotes and both dashes, on the cluster
  path, the auto-spacing path and the ellipsis path alike.
- The decision is made from the character actually sitting after the caret, so every language's
  quotes are covered at once — “…”, „…“, «…», (…), […], {…}.

### ➡️ Openers and dashes write the space they expect

- The mirror case: `(`, `[`, `{`, `"`, `“`, `‘` and the dashes keep the space **before** them — but
  inside quotes there was none left to keep, so they glued to the word: „Ahoj(“. They now write it
  themselves when the character before really ends a word (a letter, a digit, or a mark that closed
  one).
- Nothing is added after a space, after another opener or dash, at the start of a line, or in URL
  and e-mail fields, where an injected space would break the address.

### ➖ The en dash is a real keyboard character again

- `–` (U+2013) was never registered as a word terminator — only the em dash `—` was — so on every
  cluster and flat board it was appended to the tapped centre letters and corrupted the word being
  composed. It is now a spaced dash alongside the em dash: it ends the word, keeps the space before
  it and takes one after — „slovo – slovo“.

### 🔗 The `()` and `[]` entries in the candidate row

- The custom-row cursor pairs — `(…)`, `[…]`, `{…}`, `„…“`, `«…»`, `"…"` — open a group exactly like
  a typed bracket, so they take the same separator: tapped straight after a word inside quotes they
  now give „Ahoj (|)“ instead of „Ahoj()“, with the caret still between the brackets.
- Japanese is exempt (「…」 hugs its text, as the language requires), as are URL and e-mail fields;
  date templates and plain literals are untouched.

## 0.23.1+306

Built on Urik `0.23.1-beta`. Each app now remembers its keyboard **per fold state and orientation**,
not just per app.

### 🖥️ A layout per app · per fold state · per orientation

- Until now the layout a switch picked was remembered **per language** — one setting for the whole
  device. Choosing one board for Termux with the phone half-open and a different one folded wrote
  the same setting twice: the second choice replaced the first, and both fold states showed it.
  Size, colours and split already varied per fold state; **which keyboard** did not.
- Each app now remembers a **language and layout together, separately in every fold state and
  orientation** — folded, half-open and unfolded, portrait and landscape, six independent slots per
  app. Set Termux to a compact cluster board folded and a wide QWERTZ half-open, and each stays
  where you put it.
- The switch happens **the moment you fold, unfold or rotate**, not at the next time you tap a text
  field. The keyboard that opens is already the one that geometry belongs to.
- A fold state you have never chosen in falls back to the language that app last used, so it opens
  in something sensible rather than blank — and becomes independent the instant you pick a layout
  there.
- Activating a layout in the **Library** still sets the per-language default, which is what apps and
  fold states with no choice of their own follow.
- The bindings are carried in the **Per-app** category of a backup, so export/import and the 保存復元
  automation preserve them.

## 0.23.1+305

Built on Urik `0.23.1-beta`. The backup contract learns to say which categories start ticked, and a
running headless export can now be stopped.

### ☑️ The keyboard states its own default selection

- `LIST_CATEGORIES` — the answer an automation app draws its item picker from — now carries the
  contract's fourth field: `id⇥label⇥parent⇥on|off`. Whether a category starts ticked is the
  keyboard's to state, not the caller's to guess. The parent field stays empty because these parts
  are flat, but it is positional, so it is still sent.
- **Next-word predictions** is the one category that starts **off**. The statistics rebuild
  themselves from ordinary typing, so nothing you authored is lost by leaving them out of a backup;
  everything else — appearance, settings, layouts, the user dictionary, learned words, blocked
  words, per-app memory — still starts ticked.
- The Export / import page already read the same flag, so the in-app sheet and an automation app's
  picker now open on the same answer. An `EXPORT_STATE` that names no categories means that default
  set rather than literally everything.

### ⏹ A headless export can be cancelled

- A third broadcast action, **`shiroikuma.kxkb.action.CANCEL_EXPORT`**, stops the export that is
  running. It sits on the same exported receiver behind the same token, takes an optional `reply_id`,
  and answers nothing at all — it is fire-and-forget by design.
- The stop is a flag the write loop checks **between entries**, so the archive unwinds at a clean
  boundary. Nothing is interrupted mid-write, no thread is killed, and the process is never
  terminated.
- **A cancelled export leaves the backup folder exactly as it found it.** Every export is now built
  as `<name>.zip.part` and renamed to its real name only once the archive is complete; the partial is
  deleted on cancel and on every other failure alike. No short archive, no stray file — and because
  a `.part` is not a backup name, one can never be offered for import or counted as your last backup.
- The request that started the export still gets its single terminal answer, `ERROR:cancelled`, so a
  run is proven ended rather than left to finish unseen.
- **Safe to send at any moment**: with nothing running, after the export already finished, or for a
  different run, it is a silent no-op — not an error, not a crash.
- The in-app **Export** button writes through the same `.part` path, so a failure there can no longer
  leave a half-written backup in the folder either.

## 0.23.1+304

Built on Urik `0.23.1-beta`. The lock-screen keyboard becomes a choice, and the GNU 10c boards get
their bottom row straightened out.

### 🔒 Choose the lock-screen (before-first-unlock) keyboard

- The Direct Boot keyboard — the one you get on the lock screen after a reboot, before the phone has
  been unlocked once — is no longer hardcoded to GNU 15c. The new default is **GNU QWERTY 10c**.
- A **Lock-screen keyboard** section in the 白い熊 kxkb UI page (between Voice input and Apply to all
  keyboards) picks it: **Layout before first unlock**, listing the bundled boards with the GNU family
  first and the language boards under their native language names.
- Only **bundled** layouts are offered, by design. Custom and edited copies live in
  credential-protected storage that is unreadable before first unlock, so choosing one would silently
  render its unedited stock original; hardware keymaps aren't soft boards at all.
- The pick is stored in **device-protected storage** — the one place the lock screen can read, and
  the same mechanism the cold-start window-height cache uses, so it resolves synchronously on the
  show path with no DataStore access.
- **It cannot leave you without a keyboard at the PIN field.** The chain is: your pick, if its layout
  is still present → GNU QWERTY 10c → the built-in code QWERTY. A malformed or path-escaping id is
  rejected when read, a missing store reads as the default, and the before-unlock layout load now
  always succeeds instead of being able to fail.
- The choice travels in the Export / import **Layouts** category, so a restore doesn't silently drop
  it back to the default (device-protected preferences sit outside the settings store).

### ⌨️ GNU 10c bottom rows aligned to the grid

- On **GNU 10c** and **GNU QWERTY 10c** the bottom row held ten keys across a ten-column grid's worth
  of width — twelve units squeezed into ten, so every key in it was narrower than the letters above
  and the spacebar wasn't really three keys wide.
- The **`:`** key and the **`?,!`** cluster are gone, leaving `Shift | Tab | Esc | Ctrl | Space | ⌫ |
  ⏎ | 🎙` — eight keys, exactly ten units, flush with the rows above, with a spacebar of exactly
  three key widths.
- **Esc** and **Ctrl** now carry a smaller label font so their multi-character names fit inside the
  key instead of running to the edges.
- The punctuation moves up: the row's leftmost key becomes the **`:.,`** cluster — tap for `.`, slide
  left for `:`, right for `,` — with **`?` on the up flick and `!` on the down flick**.

## 0.23.1+302

Built on Urik `0.23.1-beta`. The keyboard joins 白い熊 自由作業盤's 保存復元 backup batch.

### 🤖 The 保存復元 state-export automation contract

- The keyboard now answers the **sister-app backup contract**, so 白い熊 自由作業盤's 保存復元
  project can back it up **headlessly** in the same run as every other app — no Activity, no taps.
- Two exported broadcast actions: **`shiroikuma.kxkb.action.EXPORT_STATE`** runs the export, and
  **`shiroikuma.kxkb.action.LIST_CATEGORIES`** enumerates the backup parts for the caller's
  checkbox picker (`id⇥label` per line). Neither is protected by a permission — **the token is the
  gate**, and both are dead until the switch below is turned on.
- The headless export goes through the **same engine as the Export / import page** — one ZIP, the
  same parts, the same format — so an automated backup restores exactly like a manual one. It
  honours a `path` extra (an absolute directory that overrides the configured backup folder) and an
  `items` extra (a comma-separated subset of category ids).
- The reply is a **fresh broadcast** carrying `OK:<path>|<bytes>|<human size>|<n> categories`, or a
  distinct `ERROR:…` line — exactly one reply per request. No Binder is ever handed over and the
  ordered-broadcast result is never relied upon, because EMUI severs both between third-party apps.
- **Progress broadcasts report real numbers, never a percentage** — `区分 3/8 — 学習した単語`, with
  structured `current`/`total`/`unit` extras alongside, throttled to at most one every 500 ms and
  always sending the final one.

### 🔑 Automation switch and token

- Two new rows sit **inside the Export / import section** of the 白い熊 kxkb UI page, right below
  the Export / import entry — because this is a backup feature, and it belongs where backup lives.
- **Automation export** — a master switch, **off by default**. Nothing external can reach the
  keyboard until it is on.
- **Automation token** — a 24-byte random secret shown abbreviated; **tap the row to copy the whole
  token**, or hit **Regenerate** on the right to roll it (pasted copies then need updating). The
  token is compared in constant time, lives in its own private preferences file, and is **never
  included in a backup ZIP**.

### 📛 One uniform backup filename

- Backups are now named **`shiroikuma-kxkb_<yyyy-MM-dd_HH-mm-ss>.zip`** — no version, no `_backup`
  suffix, no decoration — from the Export / import page and the automation path alike. Every 白い熊
  app writes its backups into one shared folder, so they must sort and read uniformly.
- The "last backup" line and the import picker now **filter by the app's own prefix**, so a folder
  full of sister-app backups no longer offers you somebody else's ZIP. Backups written under the
  old name are still recognised.

## 0.23.1+301

Built on Urik `0.23.1-beta`. The Export / import buttons get the ArcaneChat treatment.

### 💊 ArcaneChat-style button bar on the Export / import page

- The bottom button line now mirrors ArcaneChat's export/import dialog: **Cancel alone on the
  left** (closes the page), **Import and Export grouped on the right**.
- All three are **true round pills** — black fill, fully rounded corners, a thin yellow stroke,
  yellow text, compact padding, and a translucent-yellow ripple on press — replacing the two
  stretched half-width buttons.
- The grant-access button shares the same pill styling.

## 0.23.1+300

Built on Urik `0.23.1-beta`. The Export / import page gets the Kōjiki look.

### 📦 Export / import page in the Kōjiki sheet format

- The whole page now lives in **one bordered rounded box** (yellow stroke, rounded corners, inset
  from the screen edges) — the same surface as Kōjiki's export/import sheet — with a **centred
  bold title** and the dim intro line at the top.
- The **backup folder is a bordered, clearly-tappable box** of its own: a small yellow label over
  the path in bold, shown **warn-red when unset**; the last-backup line sits directly beneath it.
- A thin yellow divider separates the folder area from the checklist — bold **Select all** over
  the flat list of part checkboxes, all with yellow-tinted boxes — and a second divider closes
  the checklist before the **Import | Export buttons, side by side at equal width**.
- The All-Files-Access gate (red warning + grant button) keeps its place between the intro and
  the folder box.

## 0.23.1+299

Built on Urik `0.23.1-beta`. A small settings-page release: the 白い熊 kxkb UI page opens on the
things you reach for, each under its own heading.

### 🧭 kxkb UI page restructure

- **Export / import moved to the top of the page** — it was the very last row; now it is the
  first section, a real bold word-underlined heading (like Voice input) with the navigation row
  beneath it, separated from the rest of the page by the full-width divider line.
- **Voice input** follows as the second section, now opening its own divider block.
- **"Apply to all keyboards" is a section of its own** — the toggle no longer floats between
  Voice input and Geometry; it sits under its own underlined heading in its own divider block,
  and Geometry opens a fresh block below it.
- The now-unused divided-switch row layout was removed.

## 0.23.1+298

Built on Urik `0.23.1-beta`. The edit-once release: keyboard-look edits finally apply everywhere
by default instead of forking the one keyboard on screen, and the nine en/cs flat and cluster
boards get a redesigned bottom row.

### 🎨 Apply to all keyboards

- New **"Apply to all keyboards" toggle** at the top of the 白い熊 kxkb UI page (default **ON**),
  sitting between the Voice input section and the Geometry heading. Until now every colour, font
  or size edit stuck only to the exact (app · layout · geometry) combo the keyboard was last
  shown in — right for sizes, maddening for colours, which had to be re-set per app and layout.
- With the toggle on, an edit propagates **only the knob just touched** — never the whole look
  snapshot of the current keyboard: **style knobs** (all colours, font families, weights, bold,
  corner radius, border width) go to every geometry baseline **and** every stored per-app/layout
  fork, so they hold everywhere — including layouts and apps never customised, which inherit the
  baselines from then on; **dimension knobs** (heights, width, split, offsets, scales, display
  mode, floating rect) fan out to every app and layout but stay **confined to the geometry being
  edited**, so the folded keyboard's height can never overwrite the unfolded one's.
- Toggled off, edits behave exactly as before: strictly per current app · layout · geometry.
- Page restructure: **Voice input is now a real section heading** (bold, word-underlined, with
  its divider) like Geometry, with the navigation row beneath it; the next divider block opens
  with the new toggle and then the Geometry heading.

### ⌨️ Flat/cluster boards: bottom-row redesign

- The nine en/cs boards (QWERTY/QWERTZ 10c and the Q/QZ cluster 4c/9c families) get a rebuilt
  bottom area: **backspace moves up to the end of the home row**, **Enter to the end of the
  punctuation row**, and the bottom row becomes Shift | **!** | **?** (carrying the
  altGr/sym/Num layer flicks) | a **4-wide spacebar** | . | , | mic.
- On the flat boards the apostrophe becomes a case key — `'` normally, `"` when shifted. The Tab
  compass key is dropped and all custom width classes removed: every bottom-row key is a uniform
  width except the spacebar.

## 0.23.1+296

Built on Urik `0.23.1-beta`. The flat-board typing release: the swiping boards finally run the
real cluster typing model — the typed word always leads the bar, long-press Space commits and
learns it — and the Samsung long-press-Space swallow is fixed at its root.

### ⌨️ Flat boards get real cluster mode

- The flat swipe boards (en/cs/ru 10c, QWERTY/QWERTZ/ЯВЕРТЫ, ru 12c) never actually ran in
  cluster mode: the small `;.:`/`?,!` cluster keys live only on the numbers/symbols pages, and
  cluster bands are published from the letters page only — so the `+292` typed-word-leads-the-bar
  path, Space-commits-from-the-bar, and the long-press-Space literal-commit-and-learn escape were
  all silently inactive on-device. The letters page now also reports its flat letter keys, and
  either signal activates cluster mode.
- Result on every swiping board: the **directly typed word is always candidate #1** in the bar —
  prepended unconditionally, so a common word ("sebou") can never be crowded out of its own bar
  by frequency neighbours again — plain Space commits exactly what was typed, and **long-press
  Space commits the literal word, learns it (and boosts its personal ranking even when it is
  already in the dictionary), and enters the space** — identical to the cluster boards.

### 🛠 Samsung long-press-Space swallow

- On One UI, holding Space flashed a "Mezerník" tooltip and then did **nothing** — no space, no
  commit. Root cause: the key recycler's `setOnLongClickListener(null)` still marks a view
  long-clickable (AOSP sets the flag even for a null listener), so every key was framework-long-
  clickable; One UI popped a tooltip with the key's accessibility label, marked the long press
  handled, and swallowed the release click.
- Keys are now created explicitly non-long-clickable (backspace's real long-press listener
  re-enables itself), and the Space long-press always consumes and acts itself — when neither the
  literal-commit escape nor the punctuation popup applies, it commits the space directly, leaving
  nothing for OEM long-press machinery to eat.

## 0.23.1+295

Built on Urik `0.23.1-beta`. The Samsung release: the Galaxy Fold 5 dictation crash fixed, and the
voice timing settings turned into fine-grained sliders.

### 🛠 Samsung dictation crash

- On Samsung (Galaxy Fold 5, One UI): **every dictated sentence killed the keyboard** — One UI
  restarts the input connection after each committed text, which rebuilds the keyboard while the
  "Dictating…" indicator is visible; the cached indicator view was still a child of the previous
  suggestion bar, and re-adding it threw "The specified child already has a parent" on the main
  thread (One UI's crash-loop dialog after repeats). Diagnosed from the device's dropbox crash
  records retraced against the release R8 mapping.
- Fixed with detach-first at every indicator add-site — which also cures the inverted symptom
  (the indicator silently NOT appearing after a rebuild, because a stale parent skipped the add).
  The voice engine itself was blameless: pure-CPU ONNX, no device-specific inference issue.

### 🎚 Slideable voice timings

- **Silence before stop** and **End dictation after silence** are now sliders in the house style
  (live value while dragging, persisted on release) instead of fixed four-value pickers:
  200–2000 ms in 100 ms steps, and 1–30 s in 0.5 s steps — 3.5 s is a real choice now.
- The session-end value is stored in milliseconds (the legacy seconds key migrates transparently;
  a previously chosen value carries over exactly). **Fresh-install default: 2.5 s** (was 10 s).

## 0.23.1+292

Built on Urik `0.23.1-beta`. The spacing-and-pairs release: correct word separation around
„quotes“, brackets and parentheses on every input path, a literal Space after cursor movement, the
typed word restored to its own candidate bar on the flat boards, and valid centre-letter words
rescued on the cluster boards.

### ⌨️ Spacing around paired punctuation

- **Inside „quotes“/() words stay separated**: a commit that suppresses its trailing space (the
  cursor sits at „word“| before the closing mark) now remembers that the separator is still owed —
  the next word started there, typed or swiped, gets its space inserted automatically; any other
  input (space, punctuation, backspace, Enter, a cursor jump, a field change) cancels it. No space
  ever precedes the closing mark, and words never glue.
- **Typing right after a closing pair**: a new word begun at "(test)|" (arrow-out of the pair, the
  composition finished by the arrow) auto-inserts the separator — ")]}»" always; quote glyphs only
  when preceded by a letter, which correctly distinguishes the just-closed Czech „…“ from a
  just-opened English "…" (the same U+201C glyph); apostrophes excluded so elisions and
  contractions still glue.
- **The auto-inserted separator is pre-announced** through the expected-selection queue like every
  other text operation — committed bare it desynced the composing bookkeeping (the cursor was
  pulled back one position mid-word and the following word's letters came out REVERSED on the
  cluster boards).
- **Space after an arrow is literal**: the arrow flicks now clear the pending next-word (bigram)
  candidates, so arrowing out of a pair and pressing Space inserts a space instead of committing a
  stale prediction (tapped keys already cleared them; the flick path bypassed it).

### 🔤 The typed word in its own bar

- The flat 10/12-column boards run in cluster mode — Space commits from the bar — yet the spell
  checker excludes exact matches, so the word you actually typed ("test") was missing from its own
  candidate row. The typed buffer now leads the bar whenever it was typed on flat keys (no cluster
  ambiguity); true cluster centre-letter garbage stays excluded.
- **Cluster centre-word rescue**: the frequency-capped candidate pool truncated valid centre-letter
  words — "hit"/"fit"/"lit" on English, Czech "dít" via the folded centres, rare words like "luft".
  A singleton re-query of the exact centres merges them past the cut, ranked naturally.

## 0.23.1+287

Built on Urik `0.23.1-beta`. The swipe-and-correct release: glide typing on all the flat 10/12-column
boards with native-language ranking, one-tap correction of already-committed words with an
in-keyboard word editor, a per-language Learned words maintenance page, and a second round of
Czech dictionary cleanup.

### 👆 Swipe typing on the flat boards

- The seven 10-column boards (en/cs kxkb + QWERTY + QWERTZ, ru kxkb + ЯВЕРТЫ) and the ru 12c
  converted from compass keys to **flat tap keys with long-press (XK) accents** — swipe-capable;
  bottom-row punctuation keys at standard width, only Space stays wide.
- Eleven root causes fixed to make swiping work and rank natively, among them:
  - the detector was disabled on any layout containing a flick key — now a two-gate design
    (user setting + per-layout flat-letters capability);
  - swipe results now travel as the **ranked list** and the bar leads with the composed word
    (it used to show spell-checker neighbours and even filter the swiped word out);
  - a ring-buffer overflow silently crashed every longer swipe (“interesting”, „привет” — the
    500-point cap vs the 512-slot buffer);
  - the swipe dictionary is the **current layout language only** — English no longer shadows
    Czech/Russian — with the prewarm cache keyed to match;
  - **accent folding**: path geometry runs on accent-folded letters while the bar and the commit
    carry the real word — so swiping `dobry` finally offers **dobrý** (199k frequency) instead of
    subtitle-corpus junk, and ё-words work in Russian;
  - **the live-prune corruption**: the 50 ms ticker pruned candidates against the PARTIAL path, so
    words whose letters come later in the gesture were destroyed mid-swipe, timing-dependently —
    finalize now re-selects from the full index against the COMPLETE path;
  - the after-Enter candidate-bar wipe (the swipe's own composing hop was classified as a cursor
    jump on a fresh line) — defused via the tracker's expected-position announcement;
  - length-penalty floor and point-budget scaling so short words (“is”) and long words
    (“interesting”) both survive on the packed kxkb arrangements.

### ✎ Correct what's already written

- **Tap into any committed word**: it underlines and the bar offers correction candidates; picking
  one replaces the word in place — and commits no longer inject a double space when the text
  already continues with whitespace or punctuation.
- The **✎ chip** (leftmost in the bar whenever a word is composing — even with zero candidates,
  exactly when manual editing is the only fix) opens the **edit-word overlay**: a strip over the
  suggestion bar with the word and a drawn caret; the keyboard's own keys type into it with
  prediction inherently off; tap the text to move the caret; **✓** commits the edited text verbatim
  over the underlined word (multi-word “Jak se” and punctuation “kina?” included), **✕** cancels,
  **🗑** (a line-traced bin) removes the word from prediction — unlearn + blacklist.
- **Long-press any candidate** opens the same overlay prefilled with it (replacing the old
  full-screen removal confirmation).
- The **resize grip coexists**: long-press in the corner still activates resize; a quick tap there
  is forwarded to the ✎ chip; the grip yields while the overlay is open.

### 📚 Learned words page

- In the space-slide **Actions** column (in place of “Voice input” — the mic key covers voice):
  everything the keyboard has learned, in **per-language tabs** (native names, the current keyboard
  language preselected, **left/right swipe changes tabs**).
- The list merges implicitly **learned words** with explicit **user-dictionary entries**
  (registered words like 白い熊 included) — the explicit ones marked with a **＋ pill** in an
  aligned gutter so every word lines up; tight rows; **per-row delete without confirmation**,
  routed to the right store. A learned-word delete only forgets (typing it again relearns —
  unlike the candidate 🗑, it does not blacklist); “Delete all” clears the learned store but
  never touches your registrations.
- **GNU is excluded** — a no-prediction keyboard learns nothing.

### 🧹 Prediction hygiene

- **Single letters are never learned as “deliberate all-caps”**: a sentence-start “A” commit used
  to be stored as a preserve-case word that ranked first mid-sentence and — via the
  case-insensitive dedupe — shadowed the 4.5-million-frequency Czech “a” entirely. New single
  letters aren't learned; already-stored ones display recased to context.
- **Czech dictionary cleanup, round 2**: hand-curated removals the hunspell heuristic can't catch —
  proper-noun and slang residue the Czech speller itself accepts or both spellers reject (danny,
  donny, davey, darby, debra, deane, denver, donno, dovey, dong, gonna, wanna, dunno, gotta) plus
  the non-word “e” (14k corpus frequency) — while protecting real Czech lookalikes (davy, dogy,
  doby, dobrej). Kept in `tools/removed_manual/` and merged into `cs.removed` on every
  regeneration by `clean_dictionaries.sh`.
- The autofill coordinator no longer force-clears the whole suggestion bar on an empty inline
  response.

## 0.23.1+250

Built on Urik `0.23.1-beta`. The voice-input release: fully offline Whisper dictation on a new mic
key, plus a curated multi-layout roster (18 new layouts), per-language switcher curation with
overflow, newcomer-friendly defaults, and an add-language generator — and the test suite is fully
green for the first time.

### 🎙 Offline Whisper voice input

- **The engine**: [whisperIMEplus](https://github.com/woheller69/whisperIMEplus)'s ONNX Whisper
  recognizer (GPL-3), vendored as a git submodule (`external/whisperIMEplus`, fork branch `kxkb`
  with two hardening patches: catch-all exception handling on the engine threads — an engine error
  can never kill the keyboard — and a real `destroy()` that releases the six ONNX sessions). Only
  the UI-free engine subset is compiled; the recorder/orchestrator layer is new Kotlin
  (`service/voice/`). ONNX Runtime + WebRTC VAD, arm64 only, R8 keep rules for the JNI runtime.
- **The mic key** replaced the letters-layer right Shift on 11 layouts, drawn as a traced
  yellow-outline vector tinted like a label (no colour-emoji glyph). Firm haptic pulse on press,
  double tick on the long-press language flip.
- **Dictation follows the keyboard language** (GNU counts as English); **long-press flips** to the
  pair's other language (non-en ⇄ en, en/GNU ⇄ cs), flashed in the candidate line. Commits respect
  the house spacing rules; Japanese commits bare.
- **Continuous dictation** (default on): each pause commits that sentence and the mic keeps
  listening while the engine decodes in parallel — commits stay in speaking order and nothing said
  during a decode is lost. Ends on a mic tap, after a configurable silence (5/10/15/30 s), or a
  5-minute cap; a transcribing tail drains in-flight sentences. Settable beeps: one per committed
  sentence, three at session end (played with USAGE_MEDIA attributes — proper media routing).
- **Zero network permission, by design**: the Voice input settings page (top of the kxkb UI page,
  space-slide menu, and the mic key itself when setup is missing) walks the model setup — the
  browser downloads the ~243 MB Whisper small int8 zip from Hugging Face, the page imports it from
  disk with progress + validation (All-Files-Access, no SAF), and a button grants the mic
  permission. Model status, remove-model, VAD auto-stop + silence, auto-detect language, and
  translate-to-English options included.
- **Lifecycle**: the model loads lazily on the first mic press (overlapping your speaking), stays
  resident while used, and unloads its ~500 MB of native memory after a minute idle and on IME
  teardown. Watchdogs force-reset any wedged state; errors flash in the candidate line (background
  IME toasts are suppressed by Android). Voice is inert before first unlock (Direct Boot).

### ⌨️ Eighteen new layouts + Russian repairs

- **GNU**: 10c (13c minus the middle nav columns; Esc/Ctrl on the bottom row, single space) and
  QWERTY versions of all three widths (10c/13c/15c — chords and flicks travel with their keys).
- **English**: 10c (the GNU arrangement as predictive keys with the Cluster-4c bottom bar), QWERTY
  10c, Q cluster 4, Q cluster 9 (the cluster grids regrouped in QWERTY order).
- **Czech**: the same four, plus QWERTZ 10c and QZ cluster 4/9 (y ⇄ z), with „.“ quotes in the
  cluster frames.
- **Russian**: the garbled Cluster 8c deleted; Cluster 4c's bottom-row Ctrl replaced by a
  repeatable backspace; uniform number-row widths everywhere; new 12c (individual keys), compact
  10c (ю/ш in the bottom corners), phonetic ЯВЕРТЫ 10c, and Я cluster 4.

### 🔀 Layout curation & switcher overflow

- **Choose which layouts the switcher offers**: every Library row has a ⇄ pill — filled means
  listed in the space-slide Layouts column. Per language, backed up by Export/import, live-updating.
- **Overflow "…"**: the Layouts column caps at six + Library; the rest spill behind a "…" item
  that swaps the middle column for the extras after a deliberate dwell — slide on and release.
- **Newcomer defaults on fresh installs**: en QWERTY (+ Q clusters), cs QWERTZ/QWERTY (+ four
  cluster variants), ru ЯВЕРТЫ (+ Я cluster 4); GNU and Japanese start hidden. **Existing
  installations are untouched** — a one-time migration freezes the current curation and active
  layouts explicitly before the new defaults can apply.
- **＋ Add language**: the Library can generate a sensible standard keyboard (QWERTZ, AZERTY,
  ЙЦУКЕН, Greek, Bulgarian phonetic, RTL Arabic/Farsi, …) for any of the 15 dictionary-equipped
  languages that lack layouts — created as an editable custom layout, with the language activated
  and ready to type.
- The Library's git archive moved to the end of the page and folds by default (state persists).

### 🔧 Fixes & internals

- Layout re-sync now inserts new stock long-press candidates at their stock positions and copies
  in whole shifted faces the shadow lacks (the `+231` i-key case), with tests.
- The 12 long-standing test failures (a Kotlin default-argument/Mockito matcher trap) fixed — the
  full suite (1 924 tests) is green.
- New Robolectric coverage for the voice language-resolution matrix.

## 0.23.1+230

Built on Urik `0.23.1-beta`. Repairs the settings-UI regression `+222` shipped with, quadruples the
split-gap range, and rounds out the editor with row/column tooling and per-item resets — plus a calmer
compass guide and a cluster punctuation fix.

### Look & sizing

- **Settings-UI sliders work again.** `+222`'s per-combo rework opened the Keyboard UI on a stale
  default geometry, so every slider wrote to `folded_port` while the live keyboard read the real
  bucket — no edit had any visible effect. The UI now always follows the IME-published live geometry
  (and the same app·layout the keyboard resolves), so the sliders and the on-screen keyboard agree by
  construction — including right after installing an update.
- **Split gap up to 4×.** The see-through split gap now reaches 800 dp (was 200) — on both the Split
  slider and the on-keyboard resize drag. Already-saved gaps keep their exact size (legacy values are
  rescaled on read).

### Keyboard editor

- **Add row / Add column.** Two new buttons under the row list. Each asks WHAT — row presets
  (Letters / Numbers / Symbols / Function / Empty) or column presets (Character / Spacer / Backspace /
  Shift), each prefilled with real example keys, or *Clone row/column N* — and WHERE (at start / after
  any existing one / at end). A cloned column pads shorter rows with a spacer so alignment holds.
- **Per-colour ↺ reset.** Every colour row in the per-key editor has a visible reset that clears the
  override back to Inherited — previously you could only pick the default-looking colour, which still
  counted as a per-key modification. The button dims when already inherited; long-press on the swatch
  still clears too.

### Input

- **The compass guide is hold-only.** A plain tap on a compass key just enters it — no more guide
  panel flashing above the key (visible ever since the guide became the bordered black panel in
  `+156`). The guide appears only after a ~200 ms hold, keeps tracking the flick direction once up,
  and a flick/swipe in motion cancels it. Applies everywhere, including the BFU lock-screen keyboard.
- **No stray “+” before punctuation.** Composing a cluster word past the point where real candidates
  run out (the bar falls back to the custom toolbar) no longer auto-commits the toolbar's first entry
  when punctuation is typed: a custom entry commits only when Tab explicitly selected it — the same
  rule Space and Enter follow. The literal word is finished as-is, then the mark.

## 0.23.1+222

Built on Urik `0.23.1-beta`. Every size, look and mode setting is now truly per layout — nothing
bleeds between layouts or apps any more — plus exact-casing learning and a faster cluster Enter.

### Look & sizing

- **The Keyboard UI edits the layout you were just using.** The settings page (and its live
  preview) now targets the (app · layout · geometry) combo the keyboard was last shown in, instead
  of a shared per-geometry baseline: the preview opens at that layout's real size/split/mode, and
  every slider change sticks to that one layout only. Previously an edited copy looked like the
  unmodified stock inside the UI, and a height/split edit leaked into every layout of the geometry.
- **Keyboard mode is per layout too.** Standard / Split / One-handed / Floating is stored per
  (app · layout · geometry) exactly like the size knobs — making one layout split no longer splits
  them all. The old global mode value is retired on the first per-layout change.

### Input

- **Opening the kxkb UI / Keyboard editor keeps the current layout.** Per-app layout memory no
  longer applies to the keyboard's own settings app, so entering the UI to modify the active
  layout doesn't swap in whatever language was last used there — and previewing a language inside
  the UI is never recorded as that "app's" layout.
- **Deliberate casing is learned.** A word committed as all-caps (“OK”) or with internal capitals
  (“iPhone”) is remembered with that exact casing — even when a lowercase twin exists in the
  dictionary — and offered verbatim from then on. Works both when committing a cluster candidate
  and when long-space-committing a literally typed word. A plain “Ok” stays unlearned (it is
  indistinguishable from an auto-capital).
- **Enter commits the highlighted cluster candidate.** On cluster layouts Enter now works like
  Space and punctuation: it commits the highlighted candidate first (with no trailing space), then
  performs the field's Enter action — instead of committing the raw centre-letter buffer.
  Long-press Space, then Enter, remains the literal escape.

### Japanese

- **＋登録 reports failure honestly.** The reading→kanji registration screen shows
  登録に失敗しました when the entry didn't persist, instead of always claiming success — a dropped
  registration is visible immediately, and a successful one is offered right away while typing.

## 0.23.1+217

Built on Urik `0.23.1-beta`. Two power-user additions to the Keys layer: precise primary-glyph
positioning, and a one-gesture way to redraw a clipped keyboard.

### Look

- **Primary-glyph position sliders.** Two new centred sliders under **Keys → Primary character** —
  *Horizontal position* and *Vertical position* (−24…+24 dp, neutral 0 in the middle) — nudge the main
  character on every key: +X right / −X left, +Y down / −Y up. Saved per geometry like the other look
  knobs, applied live, and honoured by both the standard key labels and the cluster / column main bands.

### Input

- **RESHOW — swipe up to redraw the keyboard.** The topmost-rightmost key already hides the keyboard on
  swipe-down; swipe **up** on it now hides and immediately re-shows the keyboard, forcing a fresh window
  layout that clears the rare cold-start case where the bottom row is clipped. Bound across all thirteen
  on-screen layouts (cs / en / ru / gnu / column / ja) with an up-triangle hint that mirrors each layout's
  hide glyph (⌃ where hide is ⌄, ▴ where hide is ▾). On Android 11+ it is a true hide-then-reshow; older
  versions fall back to an in-place window remeasure.

## 0.23.1+215

Built on Urik `0.23.1-beta`. Extends the Czech dictionary cleanup to **every Latin-script language**.

### Dictionary

- **Foreign-word pollution removed from all Latin-script dictionaries** — Catalan, German, Spanish, French,
  Italian, Dutch, Polish, Portuguese, Slovak and Swedish (Czech was already cleaned in `+211`). The bundled
  corpus-built dictionaries had inherited foreign (mostly English) words; each language now ships a generated
  removal list of words a real spell-checker for that language rejects while English accepts, so cluster
  prediction leads with genuine native words. Native words and accepted loanwords are kept; the non-Latin
  dictionaries need no list (English words can't match their cluster bands). Roughly 56,000 foreign entries
  removed across the ten languages.

## 0.23.1+214

Built on Urik `0.23.1-beta`. A focused fix completing the per-language isolation work: the suggestion
pipeline now follows the language you are **typing**, not your primary language.

### Prediction

- **No more English leaking into other languages.** Several parts of the suggestion pipeline keyed off the
  primary language instead of the active layout language, so typing Czech could surface English: the
  contraction "I'd" was offered for the cluster taps of "od", and English pronoun capitalisation ("i" → "I")
  was applied regardless of language. Contraction injection, pronoun capitalisation, the caseless-language
  casing rules and the autocorrect-undo frequency boost now all use the language you are actually typing.

## 0.23.1+213

Built on Urik `0.23.1-beta`. Polish on top of `+211`: **next-word prediction** now respects the language
you're typing, and every transient **flash** adopts the black-and-yellow house style.

### Prediction

- **Next-word (bigram) prediction is per-language.** It was reading suggestions under the primary language
  while recording them under the language being typed, so typing Czech surfaced stale English next-words and
  never the Czech word you'd just typed (after "Teď" it offered old English pairs instead of "půjdeme"). The
  read now uses the active layout language, matching how they're recorded.
- **"Clear learned words"** (Privacy & data) now also clears the next-word/bigram table, so it is a complete
  reset of everything you've taught the keyboard.

### Look

- **House-style flashes.** Every toast in the app — confirmations, import/export results, validation and
  error messages, the space-slide language-switch flash — is now a black pill with yellow text and a yellow
  frame, instead of the system's default white toast.

## 0.23.1+211

Built on Urik `0.23.1-beta`. A modular **export / import** backup system, a **per-language**
custom-suggestion toolbar, and a deep fix to **cluster prediction** — common words now lead the bar, and
the bundled Czech dictionary is cleaned of foreign-word pollution.

### Export / import

- A new **Export / import** window (space-slide Actions column, and a link at the end of the kxkb UI page)
  backs up and restores the keyboard as a single `.zip`. Tick the parts to include — **appearance &
  colours, settings, keyboard layouts, user dictionary, learned words, next-word predictions, blocked
  words, per-app layout memory** — then Export or Import. Each part is one JSON file plus a manifest;
  import merges per row (never wipes; re-import is idempotent) and one failing part never aborts the rest.
- The backup folder is a real filesystem path (All-Files-Access, no SAF — the Library model); the page
  scans it and shows when the last backup was made.

### Suggestion bar

- **Custom suggestions are now per-language.** "Custom suggestions" is a sub-category under Suggestion bar
  with one editable row per active language; the toolbar swaps live as you switch layout language. Built-in
  defaults are English's general set with locale-appropriate marks for Czech (`„…“`), Russian (`«…»` + `№`)
  and Japanese (`「…」`).

### Cluster prediction

- **Common words now lead the bar.** The cluster word-finder no longer truncates its dictionary walk before
  ranking by frequency — so the most frequent match (e.g. Czech `Teď`) is always offered, not dropped.
- **Cluster suggestions are scoped to the layout language**, so a Czech cluster no longer surfaces English
  words from the merged dictionaries (and the expand pane uses the layout language, not the primary one).
- **Your own typing is prioritised**: usage and word-learning are now recorded under the language you're
  actually typing, and cluster candidates are boosted by how often you've typed them — a word you use a lot
  climbs to the top.

### Dictionary

- The bundled corpus-derived dictionaries inherited foreign words (the Czech dictionary literally contained
  English `bad/bed/bag`). The **Czech dictionary is now cleaned** with a generated removal list — words a
  real Czech spell-checker rejects while English accepts (7681 words), keeping genuine Czech loanwords and
  inflections. The loader auto-loads any `dictionaries/<lang>.removed`; regenerate via
  `tools/clean_dictionaries.sh`.

### Input / fixes

- Space-slide: the left Actions column's "Languages" entry is now **"User dictionary"** (the language
  switcher remains its own column). The Export/import action buttons no longer clip their bottom border.

## 0.23.1+204

Built on Urik `0.23.1-beta`. A **unified, per-language user dictionary** — your own words, text
shortcuts and Japanese reading→kanji registrations in one place, offered first as you type — plus a
reworked Japanese candidate flow and a GNU-layout Tab fix for terminal/Emacs use.

### User dictionary

- A new top-level **User dictionary** settings page (under Auto-Correct) lists every entry with add,
  tap-to-edit and delete. The house-style dialog covers three kinds: a plain **word**, a
  **shortcut → expansion** (e.g. `omw` → `on my way`), and a Japanese **reading → kanji**.
- Entries are **strictly per-language** — a word taught in one language is never offered while typing
  another. The add dialog shows languages by their **native name** (日本語, Čeština, …) and omits `ja`
  (its own kind) and the no-prediction `gnu`; the 日本語 kind sits on its own row.
- Backed by a new per-language store (database migrated to v9); existing Japanese registrations are
  carried over. Designed to plug into the planned granular export/import.

### Prediction

- The user's own entries are **heavily prioritised**: used once they sit near the top, used twice or
  more they become the **#1 candidate**, ties broken by frequency.
- They surface on **every layout** — matched accent-folded, and **band-by-band on cluster layouts**,
  so a registered word reappears from just its opening cluster taps. Your typed (learned) words are
  now surfaced and frequency-ranked on cluster layouts too.

### Japanese

- Registrations live in the user dictionary and **lead the candidate row** from the first matching
  kana. The in-keyboard **＋登録** flow and the editor write to the same store; the old hidden
  Japanese-only editor is gone.
- Candidate selection follows the **traditional flow**: **Space and Tab advance** the highlighted
  candidate, **Enter (確定) commits** it (and is consumed — a second Enter is a newline), and a tap
  commits directly.

### Input / fixes

- **GNU (no-prediction) layouts:** Tab and Space no longer cycle or commit the static custom toolbar
  — those entries are tap-only there. On a GNU layout **Tab now sends a real Tab key event**, so apps
  like GUI Emacs run their TAB command (completion / indent) instead of inserting a literal tab
  character — fixing Tab-completion in the minibuffer.

## 0.23.1+198

Built on Urik `0.23.1-beta`. **Compass long-press extra keys** with slide-to-pick and a lock, two
per-geometry glyph-size sliders, a Japanese-punctuation toolbar, and a thorough rework of keyboard
sizing — including a definitive fix for the cold-start clip.

### Compass long-press extra-key strip

- **Long-press a GNU compass key** to raise a strip of extra characters above the compass: slide
  right to pick (release commits), left onto a **🔒 lock** to keep the strip up and tappable, or pull
  down to cancel. The glyphs are a curated table derived from Multiling-O `XK:` data.
- The strip packs its glyphs tightly with a fast linear cursor and wraps onto more rows when narrow;
  the compass preview is enlarged into a bordered black panel with a yellow border.
- **Per-geometry glyph-size sliders** for the compass and the extra-key strip, grouped with the key
  preview toggle under a new **Popups** subsection of the **Keys** settings page.

### Keyboard sizing

- **Per-(app·layout·geometry) size resolved synchronously**, so the keyboard comes up correctly sized
  instead of clipping a frame late; a screen-fit clamp keeps a too-large saved size on-screen.
- **Constant keyboard height across all pages** (letters / symbols / number pad) — alternate pages no
  longer change the keyboard height.
- A **per-app “reset to default” button** at the bottom of the Keyboard UI page, scoped to the app you
  were typing in.
- **Cold-start clip fixed for good** — the keyboard height is cached per app·geometry in
  device-protected storage and applied before the window is measured, so a slow first-open build no
  longer leaves the bottom row clipped (or the keyboard lifted off the bottom). Verified on-device
  against a forced build-race.

### Japanese

- A **Japanese-punctuation toolbar** on the bottom row of the Japanese layouts (`「」 ？！ 。、 〜`).

### Fixes

- The candidate bar’s **expand triangle (▾) no longer multiplies** on keyboard rebuilds — the rebuild
  was re-adding it as a suggestion.

## 0.23.1+172

Built on Urik `0.23.1-beta`. A **NexDock XL** focus: a Czech variant with dead keys on the physical
keyboard and a **fully reassignable** hardware keymap (move Ctrl/Alt anywhere), plus **per-layout**
keyboard sizing and a resize-gesture fix.

### NexDock XL CZ + dead keys on the physical keyboard

- New **NexDock XL CZ** layout — a no-prediction Czech variant of the NexDock XL hardware keymap: a
  Czech number row (`ě š č ř ž ý á í é` and `ů` as the base faces, digits on Shift) and Czech
  quotes/brackets (`„ " ’ ( ) [ ]`).
- **`´` and `ˇ` are dead keys on the physical keyboard** — press the accent, then a letter, to compose
  (`´`+a → á, `ˇ`+c → č); held modifiers stay transparent. The hardware keymap commits straight to the
  field, so no prediction candidates ever appear while typing on the physical board.
- The base **NexDock XL** top rows gained the curly quotes `“ ”` and reshuffled brackets.

### A fully reassignable hardware keymap

- **Modifier chords now follow the remap.** Holding Ctrl/Alt and pressing a key sends the modifier with
  the **remapped** key — `Ctrl` + the key that types “g” → `C-g`, not the physical g — so Emacs-style
  chords match your layout, not the key labels. Action chords work too (`C-Space`, `C-←`, `S-Tab`).
- **Ctrl / Alt / Meta are layout-driven and fully reassignable.** Assign a modifier to **any** key
  (Caps → Ctrl), reassign a Ctrl/Alt key to a regular letter, or turn a modifier key into an action
  (Alt → Enter). Shift stays native, so Shift+navigation and the caps interplay keep working.
- A **HIDE key** sits at the right end of the top (F-key) row on both NexDock layouts — tap it to
  dismiss the on-screen keyboard while the physical keyboard keeps typing.
- On both NexDock layouts the **Ctrl and Alt keys are swapped** — the physical Ctrl keys act as Alt,
  the Alt keys as Ctrl.

### Per-layout keyboard size

- Keyboard size — height, width, bottom-lift and split — is now remembered **per app, per layout, per
  geometry**. Resizing one layout (e.g. the tall NexDock) no longer changes the size of your other
  layouts; the Keyboard-UI sliders still set the general default.

### Fixes

- The on-keyboard **resize gesture no longer opens the space-slide menu**: long-pressing the top-left
  grip and dragging now resizes cleanly instead of popping the layout/language menu.

## 0.23.1+164

Built on Urik `0.23.1-beta`. The keyboard now works **on the lock screen** before first unlock,
**remaps a physical keyboard** to a layout, and turns the **toolbar shortcuts** into real actions.

### Type on the lock screen (before first unlock)

- kxkb is now usable **before the device is first unlocked** (Direct Boot) — pick it on the lock
  screen to enter your PIN. It comes up as the bundled **GNU 15c** keymap (no prediction, so no
  dictionary is needed) and restarts itself into the full keyboard the moment you unlock.
- Built to be **un-brickable**: while locked, every credential-protected store (settings, the
  database, custom layouts, even the error log) is bypassed or made safe, so nothing on the lock
  screen can crash.
- The lock-screen keyboard renders **tall, gapless and edge-to-edge with double-size glyphs** for fast
  PIN entry; your normal look is untouched.

### Remap a physical keyboard

- A layout can now drive a **hardware (Bluetooth/USB) keyboard**: switch to a `hardwareKeymap` layout
  and the physical keys produce that layout's characters by position — Multiling-style, no separate
  setting.
- Ships a **NexDock XL** GNU keymap matching that board (every key with an explicit shifted face); it
  stays active across apps and keeps working with the on-screen keyboard hidden.

### Functional toolbar shortcuts

- The custom-toolbar / layout shortcuts now **act** instead of typing their label: **cursor-pair**
  tokens (e.g. `"…"`, `(…)`) insert the pair and drop the cursor between them; **`[Paste]` / `[Copy]`
  / `[Cut]` / `[All]` / `[Tab]`** run the editor action; **`{{`-date** tokens insert a formatted date.
- The **custom toolbar with candidates now shows in no-prediction layouts** (e.g. GNU), which have no
  suggestion strip.

## 0.23.1+156

Built on Urik `0.23.1-beta`. A dedicated **Number pad**, a way to **re-sync** edited layouts with
bundled updates, and a clearer **Library** list.

### A dedicated Number pad

- A new **Number pad** page — a 6×4 calculator grid (1–9 / 0 with the math and punctuation operators),
  re-derived from the Multiling “num” keypad — reachable on **every** layout via a **“Num”** flick on
  the layers key (the `Ctrl` / `⇥` key that also hosts `sym`).
- Its bottom-left is a **`←`** back-to-letters key, and the page is **height-matched to the letters
  layout** so switching to it never resizes the keyboard.
- **Number-entry fields no longer force the symbol page** — focusing a numeric field stays on the main
  letters layout instead of jumping to “sym”.

### Re-sync an edited layout with its stock

- An edited layout (a **shadow** of a bundled one) can now **Re-sync from stock**: it pulls in later
  bundled-asset changes — new keys, flicks and pages — **without discarding your edits** (an additive,
  identity-matched merge).

### A clearer Library

- Stock and custom layouts now **coexist** in the list, each tagged with a **`stock` / `custom` pill**
  (a custom no longer hides the stock it derives from).
- **Rename** any layout in place — custom layouts, and the bundled stock ones too via a persisted name
  override (the bundled asset itself stays read-only).

## 0.23.1+147

Built on Urik `0.23.1-beta`. A large release: the whole **Library** (browse, a visual editor, and a
git archive), **Keyboard-UI parity** with the futokxkb reference (floating mode, key preview, a mode
picker, per-row sizing), and Czech / Japanese input work.

### The Library — browse every layout

- A **Library** screen listing every layout grouped per language, each rendered as a **true live
  keyboard** (the real renderer + the resolved look) without switching to it; **Activate** makes it a
  language’s active layout in place.
- **Duplicate / Delete** layouts into an app-private custom store; a custom copy can **shadow** the
  stock it derives from (the stock returns when the copy is deleted).
- Full **futokxkb key-type capabilities** are now representable, editable and rendered: multi-state
  **case** keys, **column** (vertical predictive band), **cycle**, first-class **macro** / **chord**
  tap keys, per-key **appearance** and **attributes** (named widths, tri-states) — and the shipped
  cs/en/ru/gnu/ja layouts were re-imported at full fidelity.

### Visual Keyboard editor

- A View-based editor mirroring futokxkb: a tappable live preview + the editable grid, a recursive
  per-key editor (9 key types, slot drill-in, breadcrumb, single-key preview, appearance/attributes),
  **Special-key** and **Icon** pickers, alt-pages, per-key width sliders, top-bar and number-row.
- Reached from the **space-slide** and **Settings**; editing the active keyboard auto-creates an
  editable copy. **Export YAML** for portability with futokxkb.

### Git archive (in-app JGit, Library-tab only)

- A real git repo at a **settable real path** (All-Files-Access, an in-app folder picker — no SAF):
  **init**, **import**, and **commit** the whole effective layout set under clean names.
- **HTTPS remotes** — set origin, **clone / pull / push** (token stored in app settings).
- **History browser** — browse the commit log and **preview / restore** a layout as it was at any
  commit (live-rendered from the git blob).
- **Commit messages** — committing prompts for a description; the **Keyboard editor** has its own
  **Commit** with the message pre-stamped `<language> · <layout-id>:`.

### Keyboard look & modes

- A per-geometry **Mode** picker (Standard / Split / One-handed L·R / **Floating**), also reachable
  from the space-slide.
- **Floating keyboard mode** — a movable, resizable floating panel (drag grip + corner handle),
  position and size persisted per geometry.
- **Key-preview popup** — a magnified glyph above a pressed character key (on by default; toggle in
  Keys → Key body).
- New look knobs: **functional-key colour** (functional keys distinct from letters) and independent
  **top-row / bottom-row height**.
- **Split keyboard** with a see-through gap (the app shows through), and a custom **suggestion row**.
- All Settings dialogs — Library, theme, and the choice pickers — are the black / yellow house style
  (including yellow list-item text and the selected radio).

### Czech & Japanese input

- **Czech dead keys**: `´ ˇ ¨ ˚ ¯` are combining now — type the diacritic then the base letter and it
  composes (`ˇ`+`c` → `č`, `´`+`e` → `é`, `˚`+`u` → `ů`); shift composes too.
- **Japanese**: no trailing space when Space commits a conversion candidate; **register an unknown
  reading→kanji** (a `＋登録` chip → a registration screen → it’s offered afterward, and replaces the
  composing reading on save) with a **user-dictionary editor**; Japanese layouts **never
  auto-capitalise** (katakana only on an explicit Shift).

### Fixes

- **Enter** committed a newline that single-line fields turned into a **space** (search boxes, the
  browser address bar) — Enter now performs the field’s action or a real key event.
- The **half-keyboard on cold start** (bottom row clipped at a large Height scale) is fixed.
- Japanese selections weren’t learned when `ja` wasn’t the primary language; cluster punctuation,
  casing, contractions and auto-spacing refinements throughout.

## 0.23.1+72

Built on Urik `0.23.1-beta`. The features below are cumulative across the fork so far.

### Cluster-word prediction (DAWG-constrained)

- Constrained depth-first walk over the `.urik` dictionary trie: each cluster tap is ambiguous over
  a band of letters, and the keyboard enumerates every dictionary word whose accent-folded letters
  match all positions, ranked by frequency.
- Accent-folding (NFD strip + lowercase) so accented dictionary letters (`á`, `č`, …) match a
  base-letter cluster — prediction works for Czech, Russian and other diacritic-heavy languages.
- The reconstructed allowed-set comes from the typed centres plus the active layout’s cluster
  bands, which the renderer publishes to the spell checker on every letters-layout build.
- **Space commits** the highlighted candidate — for the word being typed and for the empty-buffer
  **next-word (bigram)** prediction. The commit re-applies the sentence-start capital.
- **Tab** (the “tap” key — a flick-bound `⇥`) **advances** the highlight across exactly the
  candidates shown in the bar, wrapping around; it falls back to a literal `KEYCODE_TAB` when there
  is nothing to cycle.
- **Long-press Space** (held, not slid) inserts a **literal space**, committing the typed buffer
  as-is — the escape for when you want a space, not the word.
- **FUTO-style candidate line**: instead of three fixed cells, the bar lays candidates out at their
  natural width, left to right, showing **as many as fit**; the candidate pool for cluster layouts
  is raised to 16 end-to-end so the line fills.
- **Expandable ▾ pane**: overlays the keys with a scrollable wrapping grid of the full long tail
  (a fresh DAWG walk, up to ~48 candidates; the bigram list in the next-word state); tap a chip to
  commit, `▴` to collapse.
- **Cluster-typing casing**: the keys render uppercase under auto-shift (matching the letter keys),
  but the suggestion bar stays in dictionary case — the sentence-start capital is applied on commit,
  not by offering shifted candidates.

### New key kinds & imported layouts

- **8-way compass keyboard engine** and a full **GNU** compass layout + language (`zxx`), with
  Emacs-style modifier-chord keys and function/layer bindings.
- Imported 白い熊’s **FUTO-v2 layouts** for cs / en / ru / ja / gnu: cluster, compass and column
  layouts, with `3p2` width variants and the Japanese `gojūon` and `ketai` flick layouts.
- **Layout registry** (`assets/layouts/registry.json` + `LayoutRegistry`) with per-language
  active-layout resolution (active → registry default → bundled), designed for *many* layouts per
  language and width variants grouped by family.
- **Per-key column widths** honoured from the layout JSON (shift = 1 column, space spans, etc.),
  with consistent bottom-bar sizing across layouts.
- **Shifted faces** — keys show the shifted/uppercase variant (or katakana for Japanese) when shift
  or caps-lock is engaged.
- **Secondary characters on keys** — top-row, bottom-row and flick-side glyphs rendered with their
  own font, weight, size, colour and per-side margins; cluster main-band rendering drawn at full
  primary size.
- The YAML→JSON layout converter (`tools/gnu_yaml_to_json.py`) extended for locale/script,
  `gap`/`case`/`shifted`, native action keys, cluster bands and per-key `width`; the parser infers
  a `PUNCTUATION` type for flick keys whose centre is sentence punctuation.

### Look, theme & the Keyboard-UI page

- **HighContrastYellow** black-and-yellow theme as the shipped default; square keys + bold labels.
- **Per-geometry look store**: a nullable knob set resolved per (language · layout · geometry) and
  overlaid onto the adaptive dimensions through a single seam — corner radius, key border width,
  bold labels, label weight, font scale, key height/width scale, horizontal/vertical key spacing,
  bottom-lift and hint sizing.
- **Settable colours** with an **RGBA picker** and colour swatches: keyboard background, key
  background, key text, key border, the **caps-lock Shift-glyph** colour, and the suggestion bar
  (background / font / weight / size / colour).
- **Font system**: system, monospace or **imported font files**, chosen via a glyph picker; applied
  to keys, hints and the suggestion bar.
- **See-through bottom-lift** — transparent container / root / IME window so the app shows through
  the gap below the keys and the narrowed sides.
- Key labels honour case (Button `textAllCaps` disabled).
- **HALL-sensor fold detection** for foldables (hinge popcount → folded / semi / unfolded), with a
  screen-area fallback.
- A **logical Keyboard-UI settings page** — Geometry / Keyboard (Height · Width · Key spacing) /
  Keys (Primary character · Secondary character · Key body, each with font/weight/size/colour) /
  Rows (Suggestion bar · Top · Bottom) / Compass keys / Cluster keys — instead of a flat list.
- A black/yellow **app-shell UI** and a restructured kxkb settings page, with a themed dialog look
  and a cold-start look-seed cache so the first render is already the right size.

### Resize & switching

- **Seamless on-keyboard resize gesture** — drag height / bottom-lift / width hot-points and the
  keyboard reshapes live, persisting per geometry.
- **1D spacebar-slide switcher** — slide the spacebar any direction for a 3-column menu: actions
  (kxkb UI / Languages / All settings / system keyboard chooser), other active languages, and the
  current language’s layouts; continuous gesture with under-finger highlight, release-to-pick,
  release-outside-to-cancel.

### Languages, fields & input

- **Removed the 3-active-language cap** — Czech, English, Russian and Japanese coexist.
- **Per-app layout-language memory** — each app reopens in its last-used language.
- **Code / no-prediction field mode** — auto-detected raw-input fields suppress prediction and
  auto-caps, plus a manual “No-prediction mode” setting.
- **Real `Tab` key** emitting `KEYCODE_TAB` (symbols page).
- **Punctuation auto-spacing** — a committed word’s trailing space is eaten when `.` `,` `:` `;`
  `!` `?` follows, the mark attaches to the word, and a fresh trailing space is added.
- The **spacebar shows the language’s native name** (English, 日本語, GNU, …).
- **App interface-language** (per-app locale) setting, independent of the phone locale.

### Identity & packaging

- Repackaged as the FLOSS fork **`shiroikuma.kxkb`** / label **“白い熊 kxkb”**, installable
  side-by-side with upstream Urik and any other keyboard.
- The **熊 launcher icon** (yellow-on-black wordmark).
- Fork versioning (`<VERSION_NAME>+<BUILD_NUMBER>`, monotonic `versionCode`) and release signing
  from a dedicated keystore.
- The keyboard reports **its own name in non-English locales** (18 locales previously still said
  “Urik …”).

### Upstream Urik bugs fixed along the way

- `buildApk` configuration-cache failure that silently skipped the build-number bump.
- Japanese kana-kanji conversion when `ja` is not the primary language.
- The `さ` flick being cancelled by the parent view on longer swipes.
- Half-keyboard clip on the first cold start after an app update (the mode flow built the first
  input view with no dimensions; dimensions are now seeded synchronously from the live posture).

### Tooling & docs

- Skills: **build-apk** (build + transfer), **upstream-new-version** (rebase onto a new Urik
  release), **publish-version** (this release flow).
- Project docs: `CLAUDE.md`, `docs/PLAN.md` and `HANDOFF.md` recording the runtime-store-vs-git-
  archive architecture, the futokxkb design-reference directive and the milestone sequence.
