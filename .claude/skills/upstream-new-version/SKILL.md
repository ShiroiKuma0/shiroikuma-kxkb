---
name: upstream-new-version
description: Rebase the shiroikuma-kxkb fork onto a new upstream release of urikdev/Urik (the keyboard this is forked from). Use when the user says a new upstream Urik version/tag is out, asks to update/sync to upstream, bump to the new Urik release, check for a new version, or rebase custom onto the latest upstream tag.
---

# Rebase the fork onto a new upstream Urik release

This codifies the "new upstream version" half of the fork workflow. Goal: move `main` to the new
upstream release tag, replay our `custom` customizations on top, and produce a fresh `+1` build.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** Same hard rules as
> everyday development (see CLAUDE.md). After the rebase + build you stop and let the user test; you
> only `git push` when they explicitly say **"Push"**.

## Background — branch model & versioning

- `upstream` = `https://github.com/urikdev/Urik` (https). `origin` = `git@github.com:ShiroiKuma0/shiroikuma-kxkb.git` (ssh).
- **`main` tracks upstream releases by TAG** (Urik tags every beta, e.g. `v0.23.1-beta`). We base on the
  latest **release tag**, not bleeding `upstream/main`.
- **`custom`** carries all our work, rebased onto `main` on each new release.
- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream**; we drop any `-beta` suffix
  from `VERSION_NAME` (upstream `0.23.1-beta` → `VERSION_NAME=0.23.1`).
- `BUILD_NUMBER` is **our** fork increment; it **resets to `1`** on each new upstream version.
- Fork `versionName = "<VERSION_NAME>+<BUILD_NUMBER>"`, `versionCode = VERSION_CODE * 10000 + BUILD_NUMBER`.
  So when upstream's `versionCode` climbs (64 → 65), the new line's codes (`650001`, …) all exceed the
  previous line's (`640001`, …), keeping upgrades monotonic.

## Steps

1. **Check for a newer upstream release:**
   - `git fetch upstream --tags`
   - `git tag --sort=-version:refname | head` — newest Urik tag (also accept `-beta`/`-rc` tags; Urik
     ships betas). Compare against our current base (the commit `main` points at).
   - Read the new tag's declared version:
     `git show <tag>:app/build.gradle.kts | grep -E 'versionCode|versionName'`.
   - If nothing newer than our base, stop and report "already current".

2. **Advance `main` to the new release tag** (no fork work lives on `main`):
   - `git checkout -B main <newtag>`

3. **Rebase `custom` onto the new `main`:**
   - `git checkout custom`
   - `git rebase main`
   - Resolve conflicts so **all** our customizations survive (see the table + the per-feature notes
     below). Reconcile, don't drop. If upstream restructured a file we patch, port our change to the
     new structure rather than forcing the old diff. **If conflicts are significant, stop and plan
     with the user** before continuing (see `~/.claude/CLAUDE.md` rebase-cadence rule).

4. **Update versioning in `gradle.properties`:**
   - Set `VERSION_NAME` (new upstream `versionName`, `-beta` dropped) and `VERSION_CODE` (new upstream
     `versionCode`).
   - **Reset `BUILD_NUMBER` to `1`.**

5. **Verify our customizations are intact** after resolving the rebase:

   | What | Expected value | Where |
   | --- | --- | --- |
   | Installed app id | `shiroikuma.kxkb` | `gradle.properties` → `APP_ID` |
   | Code namespace | `com.urik.keyboard` (unchanged from upstream — never rename) | `gradle.properties` → `APP_NAMESPACE` |
   | App / IME label | `白い熊 kxkb` | `app_name`, `ime_name`, `ime_label` in `app/src/main/res/values/strings.xml` |
   | Launcher icon | 熊 wordmark (yellow on black) | `mipmap-*/ic_launcher*.webp`, `drawable/ic_launcher_background.xml`, `ic_launcher-playstore.png` |
   | Fork version logic + signing | `forkVersionName`/`forkVersionCode`, keystore.properties signing, `buildApk` task, `lint { checkReleaseBuilds = false }` | `app/build.gradle.kts` |
   | `namespace = APP_NAMESPACE`, `applicationId = APP_ID` | property-driven, not hardcoded | `app/build.gradle.kts` |
   | Feature patches | every shipped phase's changes (see `docs/PLAN.md` + the cluster-prediction-testing skill once it exists) | their source files |

   Conflict-prone files: `gradle.properties`, `app/build.gradle.kts`, `app/src/main/res/values/strings.xml`,
   and — as feature phases land — the keyboard/prediction sources we patch (`SuggestionPipeline.kt`,
   `UrikDictionary.kt`, the `service/` input handlers, layout JSON/model, etc.).

   Sanity check the script still evaluates:
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew :app:tasks --console=plain | head`.

6. **(When cluster prediction exists)** run the cluster-prediction static audit / on-device trace per the
   `cluster-prediction-testing` skill before trusting the build — the prediction pipeline is the most
   rebase-fragile area.

7. **Build the new `+1`** via the **build-apk** skill
   (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildApk < /dev/null`);
   build-apk then delivers the APK automatically via `/after-build` (adb push if a phone is connected,
   else scp to skhw — no prompt). This is the first build of the new upstream line (`<newVersion>+1`).

8. **Stop.** Let the user test. Commit/push only on their explicit **"Push"**. Because the rebase rewrites
   `custom`'s history: `git push --force-with-lease origin custom`; `main` is `git push origin main`
   (fast-forward / new tag base). Run upstream-side tests too: `./gradlew :app:test` (≈112 tests) should stay green.

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history) over
  merging, so the customization set stays easy to audit and replay.
- Do **not** rename the `com.urik.keyboard` code namespace (only `APP_ID` differs) — renaming would make
  every rebase a mass-conflict. Do **not** copy FUTO source into this repo (licence).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the
body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
