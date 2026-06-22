# Plan: shiroikuma-kxkb — runtime keyboard + a git-versioned, browsable Layout & Look Library

The approved roadmap for `shiroikuma-kxkb`. It rebuilds the feature span of the old `shiroikuma-futokxkb`
(FUTO-based) fork **in spirit, not in code**, on Urik (GPL-3, pure-Kotlin, View-based, no NDK).

## Context & the FLOSS constraint

The previous fork was built on **FUTO Keyboard**, whose licence (FUTO Source First License 1.1) is **not
FLOSS** and cannot be relicensed by us. So the kxkb feature span is re-implemented clean-room on **Urik**,
which already ships cs/en/ru/ja dictionaries + layouts, a layout-agnostic flick/compass key model, glide
typing, themes/resize.

**`~/git/shiroikuma-futokxkb` is the permanent design reference** — study it to re-derive features and
conventions (its `CLAUDE.md` + the `futo-keyboard-build` / `multiling-futo-conversion` /
`cluster-prediction-testing` skills, and `kxkb/*.yaml` layout design data). **Never copy FUTO or AOSP
source into this repo.** Re-derive in spirit. Full parity with futokxkb is the goal.

Must-have languages: Czech, English, Russian, Japanese (+ the GNU `zxx` no-predict code layout). Japanese
stays on Urik's single-reading converter for now; full native Mozc is deferred.

## The two-layer architecture (locked with 白い熊)

The earlier "git is the source of truth" framing was **wrong** and was corrected:

- **The runtime store is authoritative and self-contained.** All layouts, looks and per-(geometry·app·
  language) edits live as binary/structured data in **app-private storage** (DataStore for bindings;
  app-private files / Room / assets for layout & look blobs). The keyboard **boots and runs entirely from
  this, with zero dependency on any external directory.** If the git dir is absent, unmounted or
  permission-revoked, the keyboard is unaffected. **Non-negotiable.**
- **The git library is an archival mirror + curation workbench.** A real **git repo at a Library-page-
  settable real path** (All-Files-Access `MANAGE_EXTERNAL_STORAGE`, `java.io.File`, **in-app JGit** — no SAF,
  no Termux). The app **writes** to it on capture/commit and **reads** it **only in the Library tab** — to
  browse, study prior states, restore, catalogue, and live-render the keyboard as it was at any commit.
  Never on the hot path, never on boot. **Repo unset → browse the internal library only, no git.**

### Locked decisions
1. Runtime store = authoritative binary (app-private, self-contained). Git repo = archival/curation, never
   a runtime/boot dependency.
2. Git engine = in-app JGit on a real path, settable on the Library page via a real-path folder chooser
   (not SAF). In-app commit + history + live-render-per-commit + restore. (No Termux.)
3. **Looks = 3 composed layers:** general **default** → reusable **look artifacts** (separately versioned,
   bound per geometry) → **per-key particulars embedded in the layout JSON** (the "make this key bigger"
   edits ride with the keyboard). Nullable/inherit at each layer.
4. **Width variants = separate files**, grouped by family (language → kind → width); the runtime picks the
   variant matching the current geometry width.
5. **Capture = auto-save working copy + explicit Commit** (description + auto-bump patch; manual minor/major).
6. **HighContrastYellow (black/yellow)** is the default theme; the square/bold/grid look comes from the
   per-geometry look knobs.
7. **Design for MANY layouts per language (~10+), not 2-3** — the registry, the 1D switcher and the Library
   browse must all scale.

## Core architecture (the spine)

- **Geometry key.** `service/GeometryKey.kt` — one `geometryKey(posture)` builder, 6-bucket
  `GeometryBucket` (folded/semi/unfolded × portrait/landscape). Fold state from the device HALL sensor
  (Mate XT has no Jetpack FoldingFeature) with a screen-area fallback. Never reconstruct the key inline.
- **Look composition.** `service/KeyboardLookKnobs.kt` (nullable knobs, `applyTo`/`overlay`/`encode`/
  `decode`) overlaid onto `service/AdaptiveDimensions.kt` through the single `withLookKnobs()` seam in
  `UrikInputMethodService`. Resolution: per-key (in layout) → per-geometry baseline → default. Seeded
  synchronously from a cold-start cache so the first render after an update is correctly sized.
- **Layout model + registry.** `model/KeyboardModels.kt` (`KeyboardKey`: Character / Action / FlickKey
  (+ `clusterMains` band, `shifted` CaseSelector variant, per-key `width` cells) / Spacer), parsed by
  `data/KeyboardRepository.kt::getLayoutForMode`. `data/LayoutRegistry.kt` (+ `assets/layouts/registry.json`)
  lists per-language layouts and a per-language default; `getLayoutForMode` resolves the **active layout**
  (`SettingsRepository ACTIVE_LAYOUT_BY_LANGUAGE`) → registry default → bundled `<lang>.json`.
- **Runtime store (authoritative).** App-private. Per-app·geometry → active layout + language; per-
  language·layout·geometry → bound look. Self-contained.
- **Git archive (curation).** `org.eclipse.jgit` on a real path, in the Library tab only.

## Milestones

### M1 — Usable runtime base  ✅ DONE
1A look-knob backbone + runtime store + Keyboard UI page; 1B HighContrastYellow default; 1C seamless
on-keyboard resize gesture (long-press a corner, slide); 1D **space-slide menu** (slide from the space bar
→ 3-column Actions | Languages | Layouts; release switches). Plus: per-geometry colours/fonts/weights, the
full logical Keyboard-UI hierarchy (Geometry / Keyboard / Keys{Primary·Secondary·Key body} / Rows{Suggestion
bar·Top·Bottom} / Compass keys / Cluster keys / Suggestion bar), secondary-character (top/bottom/side) row
rendering, caps-lock shift colour, cluster-key main-band rendering, shifted faces (uppercase/katakana),
space bar shows the layout language's native name, app interface-language (per-app locale) setting.

### M2 — Layout registry + import  ✅ DONE
`tools/gnu_yaml_to_json.py` extended (locale/script from filename; `gap`→spacer; `case`→normal + emitted
`shifted` variant; native action keys; full cluster bands; per-key `width` so the bottom bar aligns to the
grid). Imported 12 layouts (cs ×2, en ×4, ru ×2, gnu ×2, ja ×2 — ja = gojūon + ketai, replacing the bundled
flick). Registry + per-language active-layout resolution. **Remaining:** width-variant auto-selection by
geometry; the dead-key type; richer per-layout metadata.

### M3 — Cluster prediction (the soul)  ✅ DONE
Per-tap allowed-char-set from a cluster key; constrained DFS over the `.urik` DAWG
(`dictionary/UrikDictionary.kt`) with accent-fold (`WordNormalizer`/NFD); inject at
`service/SpellCheckManager.kt::queryUrikSuggestions` via `queryClusterSuggestions()`, gated on the active
layout carrying cluster keys; feed `service/SuggestionPipeline.kt`. Space commits the top in-cluster
prediction; the literal is 2nd. Validate on a cs/en/ru corpus; give it a `cluster-prediction-testing` skill.

### M4 — Library tab + Keyboard Editor + git archive  ✅ DONE
- **L1** ✅ internal Library tab — browse grouped per language, live previews, Activate / Edit / Duplicate /
  Delete (into an app-private custom store; the shadow model).
- **L2** ✅ visual Keyboard Editor — recursive per-key edit, structural ops, alt-pages, cross-layout copy,
  Apply / Apply-as-new / Export-YAML, per-key appearance in the JSON, a model→JSON codec; the full futokxkb
  key-type capabilities (case/column/cycle/macro/chord/appearance/attributes) re-imported at full fidelity.
- **L3** ✅ git archive — in-app JGit on a real-path-chooser-set repo (no SAF); init / import / Commit (with
  messages, incl. from the editor) / HTTPS clone·pull·push / a history browser that previews + restores a
  layout at any commit. Unset → L1 internal browse only.

### M5 — Typing refinements + full Keyboard UI parity  ◑ substantially done
✅ Dead-key composition (Czech ´ˇ¨˚¯); auto-spacing; topBar candidates + Space/Tab selection; column/cycle
key types; the space-menu features (Keyboard editor, Mode picker); functional-key colour + per-row height;
the **Mode** picker incl. **floating keyboard mode**; the **key-preview popup**; the split keyboard; the
Japanese reading→kanji registration + no-auto-caps; black/yellow Settings dialogs throughout.
Remaining tail (low priority): number/arrow rows on the look page; quick-period flick; scoped backup.

### Shipped since 0.23.1+147 (beyond the original milestones)
- **Number pad** — a canonical 6×4 calculator page (1–9 / 0 + operators, re-derived from Multiling “num”)
  reachable on every layout via a **“Num”** flick on the layers key; **height-matched** to letters; numeric
  fields no longer force the symbol page.
- **Re-sync from stock** — an edited shadow layout pulls in later bundled-asset changes (new keys / flicks /
  pages) via an **additive, identity-matched merge**, without discarding edits.
- **Library** — stock + custom now **coexist** with `stock` / `custom` pills; **in-list rename** (custom
  layouts, and bundled stock via a persisted name-override).
- **Functional toolbar shortcuts** — `service/SpecialTokens.kt`: **cursor-pair** tokens insert the pair and
  drop the caret between them; **`[Paste]`/`[Copy]`/`[Cut]`/`[All]`/`[Tab]`** run the editor action;
  **`{{`-date** tokens insert a formatted date; the custom candidate toolbar now shows in **no-prediction**
  layouts (GNU).
- **Hardware-keyboard remapping** — a top-level `hardwareKeymap` layout drives a physical (BT/USB) keyboard
  by **position** (Multiling-style, no separate setting); ships the **NexDock XL** keymap; sticky across
  apps; works with the on-screen keyboard hidden. `UrikInputMethodService.onKeyDown` + `gnu_nexdock_xl.json`.
- **Before-first-unlock (Direct Boot)** — the IME is `directBootAware` and usable on the **lock screen** as
  **GNU 15c** (no-prediction). Built **un-brickable**: while locked, every credential-protected store
  (DataStore / Room / `filesDir` / ErrorLogger) is bypassed or made safe so injection and rendering can't
  crash; it **restarts into the full keyboard on unlock**; a tall / gapless / edge-to-edge BFU look.
  `utils/DeviceLock.kt` (`isUserUnlocked` gate) is the single seam.

**Released `0.23.1+164`** (2026-06-23) — tagged `v0.23.1+164`, APK attached, on the fork's GitHub; default
branch `custom`. Earlier fork releases: `+72`, `+147`, `+156`.

## Cross-cutting
- `.urik` dict build pipeline (upstream gitignores its tooling) — our own encoder from `UrikFormat`.
- The `cluster-prediction-testing` skill + a Multiling-layout conversion skill (ports of futokxkb's).
- Per feature: read the relevant futokxkb skill/section first; reuse Urik pieces; never copy FUTO/AOSP source.
