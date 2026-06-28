# Changelog

Everything **白い熊 kxkb** adds on top of stock [Urik](https://github.com/urikdev/Urik). The fork’s
version is `<urik-version>+<our-build-number>`; the build number increments on every release and
resets to 1 on each new upstream Urik version.

## 0.23.1+204 — current

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
