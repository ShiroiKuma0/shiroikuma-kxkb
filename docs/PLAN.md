# Plan: re-implement the kxkb feature span on Urik (FLOSS)

This is the approved development roadmap for `shiroikuma-kxkb`. It rebuilds the features of the old
`shiroikuma-futokxkb` (FUTO-based) fork **in spirit, not in code**, on top of Urik (GPL-3).

## Context

The previous fork was built on **FUTO Keyboard**, whose licence (FUTO Source First License 1.1) is **not
FLOSS** — non-sublicensable, non-commercial-only, GPL-incompatible; only FUTO can relicense it. We cannot
repackage that work as FLOSS. The only legitimate path is a clean-room re-implementation on a true-FLOSS
base. After surveying FlorisBoard / HeliBoard / AnySoftKeyboard / Fossify / Unexpected / Thumb-Key and a
source-level health check, **Urik** was chosen: GPL-3, clean modern pure-Kotlin (Hilt, Room+SQLCipher,
DataStore, **View-based** UI, no NDK), ~112 test files, and it already ships **cs/en/ru/ja** dictionaries +
layouts, a **layout-agnostic flick/compass key model**, glide typing, themes/resize. **Re-derive
behaviours; never copy FUTO source.**

Must-have languages: **Czech, English, Russian, Japanese**. Japanese decision: **basic single-reading now,
decide full native Mozc later** (Urik's built-in converter is single-reading only; full Mozc — BSD, ref
`elizagamedev/android-libre-japanese-input` — is the same large native subproject on any FLOSS base).

## Build / deliver loop (every phase)

`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk` then
`./gradlew buildApk` (release-signed; → `~/tmp/shiroikuma-kxkb_<ver>_arm64-v8a.apk`, bumps `BUILD_NUMBER`).
Deliver: `adb push <apk> /sdcard/tmp/` (ask first; user installs from the file manager). Each phase ends in
a buildable, on-device-testable APK; `./gradlew :app:test` stays green. See the `build-apk` skill.

---

## Phase 0 — Identity, icon, first build  ✅ DONE

Repackaged side-by-side: `APP_ID=shiroikuma.kxkb`, label `白い熊 kxkb`, fork versioning
(`gradle.properties` + `forkVersionName`/`forkVersionCode` + `buildApk` task), release signing from a
dedicated keystore, `lint { checkReleaseBuilds = false }`, and the **solid Minchō 熊** launcher icon
(yellow on black; foreground at safe-zone size, all densities + composites + Play-Store PNG). Code
namespace kept `com.urik.keyboard`.

## Phase 1 — Quick wins / immediate pain points  ← NEXT

1. **Remove the 3-active-language cap** (白い熊's ASAP item): one constant
   `KeyboardSettings.kt:167 MAX_ACTIVE_LANGUAGES = 3` → effectively unlimited; update the toast string
   `max_languages_reached` (`strings.xml:97`) and the assertion in `KeyboardSettingsTest.kt:540`. All
   validation layers read the constant; the language switcher + settings UI scale linearly (verified).
   Now cs/en/ru/ja coexist.
2. **Easy typing tweaks:** Tab → real `KEYCODE_TAB` (add `ActionType.TAB` + `onTab()` in
   `KeyEventRouter`/`UrikInputMethodService`, reuse `OutputBridge`'s key-event sender); per-app layout
   memory (store package→layout, restore in `onStartInput` via `EditorInfo.packageName`); a code/no-predict
   field mode (extend `InputFieldClassifier`/`SecureFieldDetector`, which already special-case
   terminal/`TYPE_NULL`/password).

## Phase 2 — Compass/cluster LAYOUTS (input geometry)

Author Latin/Cyrillic compass layouts (cs/en/ru) as JSON using the existing flick model
(`model/KeyboardModels.kt` `FlickKey`, `data/KeyboardRepository.kt` parser ~290, `FlickGestureDetector.kt`,
`KeyboardLayoutManager.kt` flick commit ~128). Add at-rest multi-char key labels (only the centre char is
drawn today). Optional 8-way/diagonal flicks. Add a custom-layout import path (beside
`loadLayoutDataFromAssets` ~189) so Multiling-converted layouts can be imported.

## Phase 3 — Cluster PREDICTION (the soul)

Add a cluster-constrained candidate enumeration to the dictionary lookup: enumerate dictionary words whose
i-th character is in the per-tap allowed-char-set, with accent-fold (base-letter tap matches accented dict
letters). Inject as `queryClusterSuggestions()` alongside the clean seam `queryUrikSuggestions()` in
`SpellCheckManager.kt` (~735); reuse `scoreDictionaryCandidate` ranking + `WordNormalizer.stripDiacritics`.
The `.urik` DAWG (`UrikDictionary.kt`/`UrikFormat.kt`) is a walkable trie — add a constrained DFS (pure
Kotlin, ~200–400 LOC). Feed flick/cluster input into `SuggestionPipeline` (flicks bypass it today). Port the
commit behaviour (space commits the top in-cluster prediction; literal reachable as 2nd candidate). Validate
against a re-created cs/en/ru corpus; this is the most rebase-fragile area → its own testing skill.

## Phase 4 — Typing refinements

Dead-key diacritic composition; force-auto-caps incl. after newline; punctuation auto-spacing (em dash both
sides, ellipsis/colon/semicolon followed-by-space, closing quotes strip preceding space) via
`PunctuationLoader` + `assets/punctuation/` + `NonLetterInputHandler`; space-commits-selected / Tab-cycles
candidates (extend the JP candidate cycling to Latin); GNU/`zxx`-style no-auto-space mode.

## Phase 5 — Visual layout editor

Rebuild the in-app editor (design carried from FUTO): a model→JSON serializer (inverse of
`parseKeyFromJson`, absent today), a per-key edit screen, live preview, export/import. View-based.

## Phase 6 — Per-key appearance + look knobs

Per-key colour/font/border overrides (Urik themes are whole-keyboard — `theme/ThemeColors.kt`; add per-key
overlays in `KeyboardLayoutManager` button drawables), caps-lock colour indicator, label-weight / border
sliders.

## Phase 7 — Japanese (decision point)

Keep the single-reading converter (`KanaKanjiConverter.kt`/`JapaneseCandidateHandler.kt`); improve data /
user-learning. Decide later whether to integrate full native Mozc (deferred).

## Cross-cutting / tooling

- **`.urik` dict build pipeline** — upstream gitignores its tooling (`buildSrc/`, `raw_dicts`); write our own
  encoder from the documented `UrikFormat` so we can improve/rebuild cs/en/ru dicts.
- A **cluster-prediction-testing** skill (static audit + on-device trace), plugged into `upstream-new-version`.
- Optional: a Multiling-layout → Urik-JSON conversion skill (port of the FUTO `multiling-futo-conversion`).
