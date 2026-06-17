# Changelog

Everything **白い熊 kxkb** adds on top of stock [Urik](https://github.com/urikdev/Urik). The fork’s
version is `<urik-version>+<our-build-number>`; the build number increments on every release and
resets to 1 on each new upstream Urik version.

## 0.23.1+72 — current

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
