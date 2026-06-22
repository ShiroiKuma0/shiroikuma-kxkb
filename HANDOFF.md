# shiroikuma-kxkb — development hand-off

This document is the starting point for development on the `shiroikuma-kxkb` keyboard. Read it, then
`CLAUDE.md` (conventions) and `docs/PLAN.md` (the phased roadmap). Everything below is set up and ready.

## 1. What this is, and why

`shiroikuma-kxkb` is a true-**FLOSS** fork of [**Urik**](https://github.com/urikdev/Urik) (GPL-3.0) — an
Android keyboard. It is the successor to **`shiroikuma-futokxkb`**, which built ~140 customisation commits
on **FUTO Keyboard**. FUTO's licence (FUTO Source First License 1.1) is **source-available, not FLOSS**:
non-sublicensable, non-commercial-only, GPL-incompatible — and only FUTO may relicense it, so that fork
**cannot be made FLOSS**. We are therefore **re-implementing the entire kxkb feature span — in spirit, not
in code — on Urik.** Re-derive behaviours; **never copy FUTO source** into this repo.

Must-have languages with prediction/dictionaries: **Czech, English, Russian, Japanese** (all ship in Urik
today). Japanese is single-reading for now; full native Mozc is deferred (see `docs/PLAN.md` Phase 7).

## 2. Where everything is

- **Local repo:** `/home/shiroikuma/git/shiroikuma-kxkb` (this directory).
- **GitHub fork:** `https://github.com/ShiroiKuma0/shiroikuma-kxkb` (fork of `urikdev/Urik`).
- **Remotes:** `origin` = `git@github.com:ShiroiKuma0/shiroikuma-kxkb.git` (ssh); `upstream` =
  `https://github.com/urikdev/Urik.git` (https).
- **Branches:** `main` tracks the latest upstream **release tag** (base = `v0.23.1-beta`); **`custom`** holds
  all our work — **do all development on `custom`**.
- **Keystore:** `~/.android-keystores/shiroikuma-kxkb.jks` (alias `kxkb`); credentials in the gitignored
  `keystore.properties` (the password was surfaced once at creation — keep it saved; losing it loses the
  signing identity).
- **Roadmap:** `docs/PLAN.md`. **Conventions:** `CLAUDE.md`. **Skills:** `.claude/skills/`.

## 3. What's already done

**Current state (2026-06-23):** the roadmap is largely complete — **M1–M4 done, M5 substantially done**,
**released `0.23.1+164`**. That spans the per-geometry look system + the 白い熊 kxkb UI page, cluster-word
prediction, the whole **Library** (browse + a visual Keyboard editor + an in-app git archive with HTTPS
remotes and a history browser), and Keyboard-UI parity (a Mode picker incl. **floating mode**, a
**key-preview popup**, Czech dead keys, the Japanese reading→kanji registration, black/yellow dialogs).
**Shipped since `+147`:** a dedicated **Number pad** page, **re-sync** of an edited layout with bundled
updates, **Library stock/custom pills + in-list rename**, **functional toolbar shortcuts** (cursor pairs,
`[Paste]`/`[Copy]`/`[Cut]`, date tokens) + the custom toolbar in no-prediction layouts, **hardware-keyboard
remapping** (a `hardwareKeymap` layout drives a physical keyboard; the NexDock XL keymap), and
**before-first-unlock (Direct Boot)** — GNU 15c usable on the lock screen, built un-brickable.
The authoritative, up-to-date status is **`CLAUDE.md` → "Current status"**; the per-release detail is
**`CHANGELOG.md`**; the milestone breakdown is **`docs/PLAN.md`**. The remaining M5 tail (low priority):
number/arrow rows on the look page, quick-period flick, scoped backup.

### Phase 0 foundation (identity, icon, first build)

- **Repackaged side-by-side:** `APP_ID=shiroikuma.kxkb`, label **白い熊 kxkb** (3 strings). Code namespace
  kept `com.urik.keyboard` (do **not** rename — it keeps rebases trivial).
- **Fork versioning** wired into `gradle.properties` + `app/build.gradle.kts` (see §5).
- **Release signing** from the dedicated keystore via gitignored `keystore.properties`; `buildApk` task;
  `lint { checkReleaseBuilds = false }`.
- **Launcher icon:** solid Minchō **熊** wordmark, yellow `#FFFF00` on black `#000000` — adaptive foreground
  (safe-zone sized) at all densities, square/round composites, Play-Store PNG, black background vector.
- **Scaffolding:** `CLAUDE.md`, `docs/PLAN.md`, this hand-off, and the `build-apk` + `upstream-new-version`
  skills.

A test build (`0.23.1+1`) of this state installs and runs.

## 4. Build & deliver

Default `java` here is JDK 11 (can't run Gradle 9.x) and the SDK isn't on a default env var, so always:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
./gradlew buildApk --console=plain < /dev/null      # release-signed; → ~/tmp/<apk>; bumps BUILD_NUMBER
# fast dev iteration: ./gradlew :app:assembleDebug
```

`buildApk` copies the APK to `~/tmp/shiroikuma-kxkb_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk` and prints
the path + versionCode. Then deliver with `adb push <apk> /sdcard/tmp/` and **install from the on-device
file manager** (never `adb install`). The `build-apk` skill always ends a build by asking how to transfer
(scp to skhw first / adb push / no). See `.claude/skills/build-apk/`.

## 5. Versioning & signing

- `gradle.properties`: `VERSION_NAME` / `VERSION_CODE` **track upstream** (with `-beta` dropped — upstream
  `0.23.1-beta` → `VERSION_NAME=0.23.1`, `VERSION_CODE=64`). `BUILD_NUMBER` is **our** increment, bumped on
  every build, **reset to 1** on each new upstream version. `APP_ID` / `APP_NAMESPACE` hold the fork id +
  (unchanged) namespace.
- Fork `versionName = "<VERSION_NAME>+<BUILD_NUMBER>"`; `versionCode = VERSION_CODE * 10000 + BUILD_NUMBER`
  (Urik 64 → `640001`, `640002`, …). Monotonic across upstream bumps.
- No NDK → one universal APK; the `arm64-v8a` in the filename is just the naming convention.

## 6. Syncing to a new upstream Urik release

Use the `upstream-new-version` skill: fetch tags, advance `main` to the new tag, rebase `custom`, reset
`BUILD_NUMBER=1`, set `VERSION_NAME`/`VERSION_CODE`, verify customizations survive, build the new `+1`.
Conflict-prone files: `gradle.properties`, `app/build.gradle.kts`, `strings.xml`, and (as feature phases
land) the patched keyboard/prediction sources.

## 7. Working rules

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies (overrides the
  harness default).
- **Never commit or push until 白い熊 says "Push".** Working tree is scratch between "Push" commands. "Push"
  = `git commit` + `git push origin custom` (force-with-lease after a rebase; `main` on upstream syncs).
- **After every build, ask how to transfer** the APK via the question UI (scp first / adb push / no).
- **Never `adb install`** — 白い熊 installs from the file manager.
- **Don't rename the `com.urik.keyboard` namespace; don't copy FUTO source.**

## 8. The roadmap & current status

Full phased plan: **`docs/PLAN.md`** (milestones M1–M5). Phases: 0 identity/icon ✅ → 1 quick wins ✅ →
2 compass/cluster layouts ✅ → 3 cluster prediction (the soul) ✅ → 4 typing refinements ✅ → 5 visual
layout editor ✅ → 6 per-key appearance ✅ → 7 Japanese (single-reading ✅; native Mozc deferred). Beyond
the original plan we've since added the **Number pad**, **hardware-keyboard remapping** and
**before-first-unlock** support (see §3). Cross-cutting still open: our own `.urik` dict-build tool, a
cluster-prediction-testing skill, a Multiling-conversion skill.

**Phase 1 done (all four items):** removed the 3-active-language cap (`MAX_ACTIVE_LANGUAGES =
SUPPORTED_LANGUAGES.size` — cs/en/ru/ja coexist); Tab → real `KEYCODE_TAB` (`ActionType.TAB`/`onTab()`,
key on the en symbols page); per-app layout-language memory (`per_app_layout_languages` DataStore key,
recorded in `handleLanguageSwitch`, restored in `onStartInput`); code/no-predict field mode (auto-caps now
suppressed wherever `isSuggestionsDisabled`, plus a persistent **No-prediction mode** setting
`forceNoPredict`). Four upstream Urik bugs were also fixed: the `buildApk` configuration-cache failure
(silently skipped the `BUILD_NUMBER` bump — now fixed, so the bump is reliable); the keyboard's non-English
name (18 locales overrode `ime_name`/`ime_label` with "Urik …" — now `translatable="false"`, overrides
removed); Japanese kana-kanji conversion when `ja` isn't the primary language (looked the converter up by
primary instead of layout language); and the `さ` flick being cancelled by the parent view on longer swipes
(flick keys now call `requestDisallowInterceptTouchEvent`).

## 9. Why Urik — evaluation summary

A source-level health check (≈7.5/10) found: clean, modern, **well-tested** (112 test files), trivially
repackageable, GPL-3 with clean asset licensing, no telemetry/INTERNET. It already provides much of the
destination shape — cs/en/ru/ja dicts + layouts, a general flick/compass key model authorable in JSON,
glide, themes/resize. The flagship work still to build is **cluster *prediction*** (Urik's flicks are
deterministic; the `.urik` DAWG is a walkable pure-Kotlin trie with a clean suggestion seam — Phase 3) and
the **visual editor** (Phase 5). Risks to keep in mind: it's a young (≈2025), single-maintainer beta with a
recent dictionary-engine swap (SymSpell → `.urik`, ~mid-2026) still stabilising — we carry our own fork/CI
and can maintain it ourselves if upstream stalls.
