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

- **`build-apk`** — build the signed release APK via the `buildApk` Gradle task, then always ask (via
  `AskUserQuestion`) how to transfer it: scp to skhw (first) / adb push to `/sdcard/tmp/` / no.
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
- **After every successful build, ask how to transfer the APK** via `AskUserQuestion` (scp to skhw first /
  adb push / no) — never assume, never only ask in prose.
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

**M2 core complete** (layout registry + import): `tools/gnu_yaml_to_json.py` extended (locale/script,
`gap`/`case`/`shifted`/native-action/cluster-band/per-key `width`); imported 12 futokxkb layouts
(cs/en/ru/gnu/ja) + `LayoutRegistry` (`assets/layouts/registry.json`) with per-language active-layout
resolution.

**Next: M3** — cluster prediction (DAWG-constrained DFS). Full architecture + milestone sequence in
`docs/PLAN.md`.
