# shiroikuma-kxkb

A true-**FLOSS** fork of [Urik](https://github.com/urikdev/Urik) (GPL-3.0) — package `shiroikuma.kxkb`,
label **"白い熊 kxkb"**, installable side-by-side with upstream Urik and with any other keyboard.

This is the successor to `shiroikuma-futokxkb`. That fork built ~140 customisation commits on **FUTO
Keyboard**, but FUTO's licence (FUTO Source First License) is **not FLOSS** and cannot be relicensed by
us. So we are **re-implementing the whole kxkb feature span — in spirit, not in code — on Urik**, which is
GPL-3, pure-Kotlin, already ships cs/en/ru/ja dictionaries + layouts, a layout-agnostic flick/compass key
model, glide typing, themes/resize. **Do not copy FUTO source into this repo.**

The roadmap and current status live in **`docs/PLAN.md`** and **`HANDOFF.md`** — read those first.

## Architecture & directive (read before touching layouts/looks)

- **`~/git/shiroikuma-futokxkb` is the permanent design reference.** Study it to re-derive features and
  conventions (its `CLAUDE.md`, the `futo-keyboard-build`/`multiling-futo-conversion`/
  `cluster-prediction-testing` skills, and `kxkb/*.yaml` layout design data). **Re-derive in spirit; never
  copy FUTO or AOSP source.** Full parity with futokxkb is the goal.
- **Runtime store = authoritative, self-contained binary** (app-private). The keyboard boots and runs
  entirely from it, with **zero dependency on any external directory**. This is non-negotiable.
- **Git library = archival mirror + curation workbench** — a real git repo at a Library-page-settable real
  path (All-Files-Access, in-app **JGit**, **no SAF, no Termux**), read **only in the Library tab**, never
  on the hot path or boot. Repo unset → internal browse only.
- **Looks compose in 3 layers:** general default → reusable look artifacts (bound per geometry) → per-key
  particulars embedded in the layout JSON. Nullable/inherit at each layer.
- **Design for many layouts per language (~10+), not 2-3** — the layout registry, the space-slide switcher
  and the Library browse must all scale. Width variants are separate files grouped by family.

Full detail + milestone sequence (M1 runtime base → M2 registry/import → M3 cluster prediction → M4 Library
tab/editor/git → M5 refinements) is in **`docs/PLAN.md`**.

## Branch & remote model (same as the sister forks)

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-kxkb.git` (ssh) — our fork.
- `upstream` = `https://github.com/urikdev/Urik.git` (https).
- **`main`** tracks the latest upstream **release tag** (Urik tags every beta; current base `v0.23.1-beta`).
- **`custom`** carries all our work, rebased onto `main` on each new upstream release. **All development
  happens on `custom`.**
- **Do not rename the `com.urik.keyboard` code namespace** — only the installed `APP_ID` differs
  (`shiroikuma.kxkb`). Renaming would make every rebase a mass-conflict.

## Skills (`.claude/skills/`)

- **`build-apk`** — build the signed release APK via the `buildApk` Gradle task, then deliver it
  automatically via the global `/after-build` skill (adb push to `/sdcard/tmp/` if a phone is connected,
  else scp to skhw) — **no transfer prompt**; never pause to ask how to transfer.
- **`upstream-new-version`** — check upstream Urik for a newer release tag, advance `main`, rebase
  `custom`, reset `BUILD_NUMBER`, build the new `+1`.
- **`publish-version`** — publish the latest tested APK as a GitHub release of the fork: tag
  `v<version>`, attach the APK, refresh the README badge + `CHANGELOG.md`, keep the default branch on
  `custom`. Pin `gh` with `-R ShiroiKuma0/shiroikuma-kxkb` (the `upstream` remote otherwise wins).
- *(Planned, once cluster prediction is built — see `docs/PLAN.md`:)* a `cluster-prediction-testing`
  analogue, and a Multiling-layout conversion skill.

## Build, versioning, signing

- **Build env (this machine):** default `java` is JDK 11 (can't run Gradle 9.x). Always:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk`.
- **Build:** `./gradlew buildApk` (release-signed; copies the APK to `~/tmp` and bumps `BUILD_NUMBER`).
  Fast dev iteration: `./gradlew :app:assembleDebug`.
- **Versioning** (`gradle.properties`): `VERSION_NAME`/`VERSION_CODE` track upstream (with `-beta`
  dropped); `BUILD_NUMBER` is our increment (reset to 1 on each new upstream version). Fork
  `versionName = "<VERSION_NAME>+<BUILD_NUMBER>"`, `versionCode = VERSION_CODE * 10000 + BUILD_NUMBER`
  (Urik 64 → `640001`, …). APK filename: `shiroikuma-kxkb_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`
  (no NDK → universal APK; `arm64-v8a` is just the filename convention).
- **Signing:** release signed from gitignored `keystore.properties` →
  `~/.android-keystores/shiroikuma-kxkb.jks` (alias `kxkb`). Keep the password safe (losing it loses the
  signing identity).
- **Delivery:** APK to `~/tmp`, then `adb push <apk> /sdcard/tmp/`; **the user installs from the
  on-device file manager** (never `adb install`).

## Working rules (override harness defaults where noted)

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies — end the
  message at the last line of the body. (Overrides the harness default; global rule in `~/.claude/CLAUDE.md`.)
- **Never commit or push until the user says "Push".** Treat the working tree as scratch between "Push"
  commands; multiple uncommitted fixes can stack. "Push" = `git commit` + `git push origin custom` (and
  `main` after an upstream sync). The user tests each build on-device first.
- **After every successful build, deliver the APK automatically via `/after-build`** — never ask how to
  transfer it, never pause. `/after-build` runs `/adb-check` (unsandboxed); if a phone is connected it
  `/adb-push`es the newest `~/tmp/*.apk` to `/sdcard/tmp/`, otherwise `/scp`s it to `skhw:~/tmp/`.
- **Commit subjects:** plain descriptive summary, no prefix.

## Repo layout (upstream Urik)

- `app/src/main/java/com/urik/keyboard/` — Kotlin sources: `service/` (40+ single-responsibility input
  handlers + `SuggestionPipeline`, `SpellCheckManager`, `KanaKanjiConverter`, …), `dictionary/`
  (`UrikDictionary`, `UrikFormat`, `LevenshteinAutomaton`), `ui/keyboard/components/` (View-based renderer,
  flick/swipe gesture stack), `data/`, `model/`, `theme/`, `settings/`, `di/` (Hilt), `utils/`.
- `app/src/main/assets/` — `layouts/*.json` (incl. cs/en/ru/ja), `dictionaries/*.urik` (+ `ja_readings.txt`),
  `punctuation/`, `emoji/`, `characters/`.
- `app/src/test/` — ~112 test files (Robolectric/Mockito).
- Stack: Kotlin, Hilt, Room + SQLCipher, DataStore, **View-based** UI (not Compose), no NDK. minSdk 26,
  targetSdk 36, JDK 21.
- Upstream gitignores its `.urik` dict build tooling (`buildSrc/`, `raw_dicts`) — see `docs/PLAN.md`
  (cross-cutting) for our own dict-build plan.

## Current status

**Phase 0 complete** (identity + icon): repackaged `shiroikuma.kxkb` / `白い熊 kxkb`, fork versioning +
signing, the 熊 launcher icon.

**Phase 1 complete** (quick wins): removed the 3-active-language cap (cs/en/ru/ja coexist); Tab key →
real `KEYCODE_TAB` (symbols page); per-app layout-language memory; code/no-predict field mode (auto-caps
also suppressed in auto-detected no-predict fields + a manual "No-prediction mode" setting). Along the way
we also fixed four upstream Urik bugs: the `buildApk` configuration-cache failure (it was silently
skipping the `BUILD_NUMBER` bump); the keyboard's non-English name (18 locales still said "Urik …");
Japanese kana-kanji conversion when `ja` isn't the primary language; and the `さ` flick being cancelled
by the parent view on longer swipes.

**M1 complete** (usable runtime base): per-geometry look knobs + runtime store + the full logical
**白い熊 kxkb UI** page (Geometry / Keyboard / Keys{Primary·Secondary·Key body} / Rows{Suggestion bar·Top·
Bottom} / Compass keys / Cluster keys / Suggestion bar); HighContrastYellow default; seamless on-keyboard
resize; **1D space-slide menu** (Actions | Languages | Layouts); secondary-character row rendering, cluster
main-band rendering, shifted faces (uppercase/katakana), caps-lock shift colour, space bar shows the
language's native name, app interface-language (per-app locale) setting.

**M2 complete** (layout registry + import): `tools/gnu_yaml_to_json.py` extended; imported 12 futokxkb
layouts (cs/en/ru/gnu/ja) + `LayoutRegistry` (`assets/layouts/registry.json`) with per-language
active-layout resolution.

**M3 complete** (cluster prediction — the soul): DAWG-constrained DFS over the `.urik` dictionary with
accent-folding; Space commits the highlighted candidate, Tab advances, long-press Space = literal space;
the FUTO-style as-many-as-fit candidate line + an expandable long-tail pane; cluster-typing casing,
contractions, punctuation + auto-spacing refinements.

**M4 complete** (the curation layer): **L1** Library browse (live per-layout preview + Activate;
Duplicate/Delete into an app-private custom store; the shadow model). **L2** the visual **Keyboard
editor** (recursive per-key editor, main sections, Special/Icon pickers, YAML export, preview taps,
space-slide + Settings entry points) + the full futokxkb key-type capabilities
(case/column/cycle/macro/chord/appearance/attributes) re-imported at full fidelity. **L3** the **git
archive** — in-app JGit at a settable real path (no SAF): init/import/commit with messages, HTTPS
clone/pull/push, a history browser that previews/restores a layout at any commit, commit-from-editor.

**M5 substantially complete** (typing refinements + Keyboard UI parity): functional-key colour,
top/bottom row height, a per-geometry **Mode** picker (Standard/Split/One-handed/**Floating**),
**floating keyboard mode** (drag + resize, per-geometry), **key-preview popup**; split keyboard with a
see-through gap + a custom suggestion row; **Czech dead keys** (´ˇ¨˚¯ combining); **Japanese** — no
trailing space on candidate commit, reading→kanji registration (a `＋登録` flow + a user-dictionary
editor), never auto-capitalises (katakana on explicit Shift only); every Settings dialog in the
black/yellow house style. Fixes along the way: Enter committing a space in single-line fields; the
half-keyboard on cold start.

**Shipped since `+147`** (beyond the original milestones): a dedicated **Number pad** (6×4 calc page on a
"Num" flick, height-matched to letters; numeric fields no longer force the symbol page); **re-sync** of an
edited shadow layout with bundled updates (additive, identity-matched merge); **Library** stock/custom
pills + in-list rename (custom + a persisted name-override for bundled stock); **functional toolbar
shortcuts** (`service/SpecialTokens.kt` — cursor pairs place the caret between, `[Paste]`/`[Copy]`/`[Cut]`/
`[All]`/`[Tab]` run the editor action, `{{`-date tokens) + the custom candidate toolbar in no-prediction
layouts; **hardware-keyboard remapping** (a top-level `hardwareKeymap` layout drives a physical BT/USB
keyboard by position — `onKeyDown` + `gnu_nexdock_xl.json`; sticky across apps, works with the soft
keyboard hidden); **before-first-unlock (Direct Boot)** — the IME is `directBootAware` and usable on the
lock screen as **GNU 15c** (no-prediction), built **un-brickable** (every credential-protected store —
DataStore/Room/`filesDir`/ErrorLogger — bypassed or made injection-safe; `utils/DeviceLock.kt` is the
gate), restarting into the full keyboard on unlock with a tall/gapless/edge-to-edge BFU look;
**primary-glyph position sliders** (two centred Keys → Primary controls — `primaryOffsetXDp`/`YDp`, ±24 dp
horizontal/vertical offset of the main character; per-geometry, live-applied, honoured by both standard key
labels and the cluster/column main bands); **RESHOW** (swipe-up on the topmost-rightmost key — paired with
the existing swipe-down HIDE — hides then re-shows the keyboard to clear the rare cold-start bottom-clip:
API 30+ `requestShowSelf`, else `forceInputViewRemeasure`; a new `"reshow"` flick-action bound on the
top-right key of all 13 soft layouts with an up-triangle hint); **per-combo everywhere** (the kxkb UI +
Mode picker read/write the live (app·layout·geometry) combo the keyboard was last shown in
(`currentSizeTarget`) instead of the shared per-geometry baseline; Mode is a per-combo look knob —
`KeyboardLookKnobs.displayMode` — with the legacy global scalar as fallback-then-retired; the in-UI
preview borrows the last REAL app·layout so an edited copy shows its true size; per-app layout-language
memory skips our own settings app, so the kxkb UI/editor always opens on the current layout);
**deliberate-casing learning** (all-caps "OK" / internal-caps "iPhone" learned with their exact surface
past the case-insensitive in-dictionary short-circuit, on both the cluster-candidate commit and the
literal long-space path; ambiguous "Ok" stays unlearned); **cluster Enter commits the highlighted
candidate** (no trailing space, then the field's Enter action — mirrors Space/punctuation; long-space
stays the literal escape); **＋登録 honest save toast** (reports 登録に失敗しました when the write didn't
persist — the registration path itself is the unified `user_dictionary`, same as User dictionary → Add);
**dead-slider regression fixed** (`+222` opened the Keyboard UI on a stale FOLDED_PORT default so every
slider wrote to the wrong geometry key — the UI now follows the IME-published live geometry+combo as one
`combine` stream, loads are serialized, the IME seeds `lastRealApp`/`lastRealLayoutId` from the persisted
`currentSizeTarget` after a process restart, and the settings-UI preview resolves the same key the sliders
write); **4× split gap** (`MAX_SPLIT_GAP_DP` 200→800 dp, slider + resize drag; stored legacy `spf`
fractions rescaled on decode, new writes use `spg`); **editor Add/Clone row+column** (preset dialog —
Letters/Numbers/Symbols/Function/Empty rows, Character/Spacer/Backspace/Shift columns, or clone any —
plus an insert-position picker; short rows padded with a spacer on column clone); **per-colour ↺ reset**
(per-key editor colour rows clear back to Inherited visibly; long-press on the swatch still works);
**hold-only compass guide** (a tap or swipe never flashes the FlickPopup — it appears only after a
~200 ms hold, then tracks the flick; a flick-sized move cancels the pending guide; BFU included);
**custom-toolbar punctuation gate** (a custom entry commits on cluster punctuation only when
Tab-selected — no more auto-committed "+" when candidates run dry).

**Shipped since `+230`** (the `+250` line): **offline Whisper voice input** — the whisperIMEplus ONNX
engine as a git submodule (`external/whisperIMEplus`, fork branch `kxkb` with catch-all + real-destroy
hardening patches; only the UI-free engine subset compiles, Kotlin recorder/orchestrator in
`service/voice/`); the mic key replaced the letters-layer right Shift (traced outline vector, press/flip
haptics); dictation follows the keyboard language with a long-press en⇄cs-style flip (candidate-line
flash); continuous dictation (parallel decode, per-sentence + session-end beeps on USAGE_MEDIA); the
guided no-network model-import settings page; 60 s idle unload + watchdogs; R8 keep rules for the
JNI-bound ONNX runtime (without them the IME died at first transcription). Plus: **per-language
switcher curation** (Library ⇄ pills + the visible-layouts store) with the "…" overflow (dwell-gated
middle-column swap); **18 new layouts** (GNU 10c + GNU QWERTY 10c/13c/15c; en/cs 10c + QWERTY + Q/QZ
clusters; ru 12c/10c/ЯВЕРТЫ/Я cluster 4) and ru repairs (Cluster 8c deleted, backspace bottom row,
uniform number rows); **newbie fresh-install defaults** with an upgrade migration that freezes existing
curation first; **＋ Add language** in the Library (standard keyboards for the 15 dictionary-equipped
languages, incl. RTL); git archive at the Library page end, folded by default; the 12 long-standing
Mockito matcher test failures fixed — the suite (1 924 tests) is fully green.

**Shipped since `+250`** (the `+287` line): **swipe typing on the flat boards** — the seven 10c boards
(en/cs kxkb/QWERTY/QWERTZ, ru kxkb/ЯВЕРТЫ) + ru 12c converted to flat tap keys with XK accents; eleven
root causes fixed (detector gating, ranked-candidate bar, 500/512 ring overflow, single-language dict +
prewarm keying, **accent folding** — geometry on folded letters, real word displayed/committed —, the
**partial-path live-prune corruption** — finalize re-selects from the full index against the complete
path —, after-Enter bar wipe, length-penalty floors; see `memory/swipe-typing-10c.md`); **tap-to-correct**
(tapping a committed word feeds recompose through the pipeline → candidates in the bar; smart trailing-
space suppression) + the **✎ edit-word overlay** (in-keyboard strip, drawn caret, keys type into the
buffer prediction-free, ✓/✕/🗑; candidate long-press opens it; the resize grip forwards quick taps to the
chip, long-press still resizes — the grip is the topmost child and eats corner DOWNs, see
`memory/word-correction-edit-overlay.md`); the **Learned words page** (space-slide Actions, replacing the
Voice-input entry — per-language tabs with native names + current-language preselect + swipe-to-switch;
merged learned + user-dictionary entries, ＋ pill marker, tight rows, per-row no-confirm delete routed to
the right store; GNU excluded); **prediction hygiene** — single letters never learned as deliberate
all-caps (the learned "A" shadowed 4.5M-freq Czech "a"), stored single-letter surfaces fold at query,
`tools/removed_manual/` hand-curated dictionary removals (danny/donno/… + non-word "e") merged into
`cs.removed` by `clean_dictionaries.sh` (the hunspell heuristic can't catch them — cs accepts "Danny",
and rejected-by-both includes valid colloquial Czech).

**Shipped since `+287`** (the `+292` line): **spacing around paired punctuation** — a commit that
suppresses its trailing space (cursor at „word“| before a closing mark) sets
`InputStateManager.pendingWordSeparator`, consumed when the next word starts (typed or swiped) so words
inside „quotes“/() stay separated with no space before the closer; the mirror geometry (new word right
AFTER "(test)|" via arrow-out) via `CursorEditingUtils.needsSpaceAfterClosingPair` (")]}»" always,
quote glyphs only letter-preceded — U+201C closes cs „…“ but opens en "…"); the auto-separator is
PRE-ANNOUNCED via `ExpectedTypingOus(-1,-1,pos)` — committed bare, its selection update hit
`reassertComposingRegion` which pulled the cursor back mid-word and REVERSED the next word's letters
(every IC text op must be pre-announced!); arrow flicks clear bigram predictions (Space after arrow =
literal, not a stale candidate commit); **typed word leads the flat-board bar** (flat boards run in
cluster mode so Space commits from the BAR, but spell-check excludes distance-0 — `requestSuggestions`
prepends the buffer when `!hasClusterAmbiguity`); **cluster centre-word rescue** (valid centre words —
hit/fit/lit, dít via folding, rare luft — were pool-truncated; a singleton re-query merges them past
the CLUSTER_BAR_POOL cut).

**Shipped since `+292`** (the `+295` line): **the Samsung dictation crash fix** — One UI restarts the
input connection after every committed text, rebuilding the keyboard while the voice indicator is
visible; the cached indicator was still a child of the OLD suggestion bar and the bare re-add threw
"child already has a parent", killing the IME once per dictated sentence (diagnosed via dropbox crash
records + R8-mapping retrace; fix = detach-first at all indicator add-sites; RULE: any view cached
across keyboard rebuilds must detach before addView — see `memory/samsung-fold5-indicator-crash.md`);
**slideable voice timings** (silence-before-stop 200–2000 ms/100 ms, session-end 1–30 s/0.5 s; stored
as `voice_session_end_ms` with legacy-seconds migration; fresh-install default **2.5 s**).

**Released `0.23.1+295`** (2026-07-22; tagged `v0.23.1+295`, APK attached, on the fork's GitHub; default
branch `custom`; README badge + `CHANGELOG.md` track it). Recent fork releases: `+250`, `+287`, `+292`,
`+295` (earlier: `+72`, `+147`, `+156`, `+164`, `+172`, `+198`, `+204`, `+211`, `+213`, `+214`, `+215`,
`+217`, `+222`, `+230`). Remaining M5 tail (low priority): number/arrow rows on the look page,
quick-period flick. Full architecture + milestone sequence in `docs/PLAN.md`.
