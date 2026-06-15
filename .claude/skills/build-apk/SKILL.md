---
name: build-apk
description: Build the signed release APK of shiroikuma-kxkb (the "白い熊 kxkb" keyboard — a true-FLOSS fork of Urik) with the `buildApk` Gradle task, then always ask whether to scp it to skhw (first choice) or adb push it to the connected phone. Always build first without asking permission to build — the ONLY question you ever ask is the transfer question afterward. Use whenever the user asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the kxkb release APK and optionally send to the phone

> **Never ask whether to build — just build.** When this skill applies (the user asked
> to build, or you've made changes ready to test), run the build immediately. Do **not**
> ask "shall I build?". The **only** question in this flow is the `AskUserQuestion` about
> transferring the APK, asked **after** a successful build.

> **The push destination is ALWAYS `/sdcard/tmp/`.** Every `adb push` of the APK goes to
> `/sdcard/tmp/<apk name>` — never `/sdcard/Download/`. Create `/sdcard/tmp` if needed.

> **Never run `adb install` (or `pm install`).** You may `adb push` (only after confirming
> with the user); **the user installs the APK themselves** from the phone's file manager.

> **Never `git commit` or `git push` on your own.** Building does not include committing.
> After building (and the optional `adb push`), the user tests the build. **Only when the
> user explicitly says "Push"** do you `git commit` the changes and `git push origin custom`.
> The user's **"Push"** means *commit-and-push-to-the-fork* — unrelated to the `adb push`
> file copy.

> **ALWAYS end every build by asking — via `AskUserQuestion` — how to transfer the APK:
> `scp` to skhw (FIRST choice), `adb push` to `/sdcard/tmp/`, or not at all.** Mandatory for
> *every* successful build, even verification builds. Do not settle for asking in prose.

## Build environment (this machine)

- The default `java` is **JDK 11**, which **cannot** run Gradle 9.x. Always export JDK 21.
- The Android SDK is **not** on a default env var; export `ANDROID_HOME` explicitly.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

## Steps

1. **Note the output filename / version.** Read the version + counter from `gradle.properties`:
   - `grep -E 'VERSION_NAME|VERSION_CODE|BUILD_NUMBER' gradle.properties`
   - The APK will be `shiroikuma-kxkb_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`, using the
     `BUILD_NUMBER` value **before** the build (the `buildApk` task bumps it afterward).
   - versionCode for that build = `VERSION_CODE * 10000 + BUILD_NUMBER`.

2. **Build** (release, signed) — from the repo root:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
   ./gradlew buildApk --console=plain < /dev/null
   ```
   - `buildApk` runs `assembleRelease`, copies the signed APK to `~/tmp/<apk name>`, and
     auto-increments `BUILD_NUMBER` in `gradle.properties`.
   - It prints `>>> <path>` and `>>> versionCode <n>` (cyan) — use those to confirm the exact
     filename/code; confirm `BUILD SUCCESSFUL`.
   - First build after `git clean`/fresh checkout downloads deps + the Gradle 9.x distro — it
     can take a while; subsequent builds are seconds (config-cache warm). A cold build that may
     exceed the foreground timeout can be run with `run_in_background`.
   - **Fast dev iteration:** `./gradlew :app:assembleDebug` produces a debug-signed APK at
     `app/build/outputs/apk/debug/` (no R8/proguard, faster) — useful while iterating, but the
     shippable build is `buildApk` (release-signed). Debug installs side-by-side only if you keep
     the same `applicationId` (no suffix is configured).

3. **At the end of every build, ALWAYS ask** via `AskUserQuestion` how to transfer the APK —
   no exceptions, no assuming. Options, in this order: **"Scp to skhw"** (FIRST) / **"adb push"** /
   **"No, just build"**. Fire it as soon as `BUILD SUCCESSFUL` appears.

4. **Transfer per the answer:**
   - **Scp to skhw** — invoke the global **scp** skill (copies the newest APK in `~/tmp/` to
     `skhw:~/tmp/`). If skhw is unreachable (its tunnel is served by the phone's sshd and may be
     down), report that and offer the adb push instead.
   - **adb push:**
     - `adb devices` — confirm a device is connected.
     - `adb shell mkdir -p /sdcard/tmp`
     - `adb push ~/tmp/<apk name> /sdcard/tmp/<apk name>`
     - Verify: `adb shell ls -l /sdcard/tmp/<apk name>` (size matches the local file).
     - Never `adb install` — the user installs manually from `/sdcard/tmp/`.

## Signing

Release signing is non-interactive: `app/build.gradle.kts` reads credentials from
`keystore.properties` (gitignored). This fork uses its own keystore
`~/.android-keystores/shiroikuma-kxkb.jks` (alias `kxkb`); the store/key password is in
`keystore.properties` (and was given to 白い熊 once at creation — keep it safe; losing it means
the signing identity can't be reproduced). If `keystore.properties` is absent the release build
is unsigned and won't install — restore it (pointing `storeFile` at the `.jks`, with `keyAlias`,
`keyPassword`, `storePassword`).

## Versioning (how the numbers are formed)

- `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` **track upstream Urik** (we use the
  upstream version with any `-beta` suffix dropped, e.g. upstream `0.23.1-beta` → `VERSION_NAME=0.23.1`).
- `BUILD_NUMBER` is **our** fork increment, bumped on every `buildApk`, reset to `1` on each new
  upstream version (see the `upstream-new-version` skill).
- Fork `versionName = "<VERSION_NAME>+<BUILD_NUMBER>"`; `versionCode = VERSION_CODE * 10000 + BUILD_NUMBER`
  (Urik 64 → `640001`, `640002`, …). When upstream's code climbs, the new line's codes exceed the
  old, keeping upgrades monotonic.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
