---
name: publish-version
description: Publish the latest built shiroikuma-kxkb APK as a GitHub release of the fork — create the version tag, attach the APK, update the README's latest-release badge and the detailed CHANGELOG, ensure the GitHub default branch is `custom` so the repo page lands on our work, and write specific release notes. Use when the user says publish / release / cut a version / ship this build / make a GitHub release / publish the latest build.
---

# Publish a kxkb version to GitHub

Turn the latest tested build into a public GitHub **release** of the fork
(`ShiroiKuma0/shiroikuma-kxkb`): a version tag, the APK as a downloadable asset, an updated README +
CHANGELOG, and a default branch (`custom`) so the repo landing page shows our work.

> **This is outward-facing — it publishes to GitHub.** The user invoking this skill *is* the
> authorization. Still, summarise the exact version + assets first, then proceed. Never publish a
> build the user hasn't tested.

> **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or release notes —
> end at the last line of the body. (Global rule.)

## What gets published

The **latest APK in `~/tmp/`** (`shiroikuma-kxkb_<VERSION_NAME>+<BUILD_NUMBER>_arm64-v8a.apk`) — the
build the user just tested on-device. Derive the version from the **APK filename**, NOT
`gradle.properties` (whose `BUILD_NUMBER` is already the *next* number, because `buildApk` bumps it
after building).

```bash
APK=$(ls -t ~/tmp/shiroikuma-kxkb_*.apk 2>/dev/null | head -1)
VERSION=$(basename "$APK" | sed -E 's/^shiroikuma-kxkb_(.+)_arm64-v8a\.apk$/\1/')   # e.g. 0.23.1+72
TAG="v$VERSION"
```

If `$APK` is empty, stop and tell the user there's no built APK to publish (run `build-apk` first).

## Preconditions to check

1. **The APK matches `HEAD`.** The user pushes after testing, so `custom`'s `HEAD` should be the
   code that produced this APK. If the working tree has uncommitted source changes, or `HEAD` was
   advanced past the build, warn — the safest path is to rebuild (`build-apk`) so the published APK
   and the tag agree. Don't publish a tag that points at code the APK wasn't built from.
2. **On `custom`** (`git rev-parse --abbrev-ref HEAD` = `custom`) and pushed
   (`git push origin custom` if ahead).
3. **The tag doesn't already exist** (`git tag -l "$TAG"` empty, and
   `gh release view "$TAG"` 404s). If it exists, the version was already published — confirm with
   the user before re-cutting.

## Steps

1. **Ensure the GitHub default branch is `custom`** so the repo page lands on our README, not
   upstream's `main`:
   ```bash
   gh repo edit ShiroiKuma0/shiroikuma-kxkb --default-branch custom
   gh repo edit ShiroiKuma0/shiroikuma-kxkb \
     --description "白い熊 kxkb — a true-FLOSS fork of Urik: cluster-word prediction in any language, per-geometry theming, live resize, a spacebar switcher, compass/cluster layouts. Side-by-side, on-device, GPL-3."
   ```
   (Idempotent — safe to run every time.)

2. **Update the README badge.** Point the “Latest release” line at the new version:
   - `📥 Latest release: [\`<VERSION>\`](…/releases/latest)` — replace the version in `README.md`.

3. **Update `CHANGELOG.md`.** Keep it **specific — list everything**, the way `CHANGELOG.md`
   already does. Rename the `## <old> — current` heading to the released version and add a fresh
   `## <new> — current` section above it summarising what changed **since the last tag**:
   ```bash
   git log --oneline <previous-tag>..HEAD     # the commits to fold into the new section
   ```
   Group them by area (prediction / layouts / look / input / fixes), one specific bullet each — not
   raw commit subjects. On the very first publish there is no previous tag; the existing
   `0.23.1+72` section already enumerates the whole fork.

4. **Commit the docs** on `custom` and push:
   ```bash
   git add README.md CHANGELOG.md
   git commit -m "Release <VERSION>: README + changelog"
   git push origin custom
   ```

5. **Tag and release.** Annotated tag at `HEAD`, then a GitHub release targeting `custom` with the
   APK attached and the new CHANGELOG section as the notes:
   ```bash
   git tag -a "$TAG" -m "白い熊 kxkb $VERSION"
   git push origin "$TAG"
   gh release create "$TAG" "$APK" \
     --target custom \
     --title "白い熊 kxkb $VERSION" \
     --notes-file <(sed -n '/^## <new-version>/,/^## /p' CHANGELOG.md | sed '$d')
   ```
   (Or pass `--notes` with the section text directly.) Keep the APK asset name as built
   (`shiroikuma-kxkb_<VERSION>_arm64-v8a.apk`).

6. **Report** the release URL (`gh release view "$TAG" --json url -q .url`) and confirm the default
   branch is `custom`.

## Notes

- `git push`, `gh` and `scp` need `~/.ssh` / `~/.config/gh`, which the command sandbox blocks — run
  the push / `gh` / tag steps with the sandbox disabled (`dangerouslyDisableSandbox: true`), same as
  the other fork skills.
- This skill **does not build** — it ships whatever is newest in `~/tmp/`. If the user wants a fresh
  build first, that's the `build-apk` skill's job.
- `main` stays tracking upstream; releases are always cut from `custom`. After an
  `upstream-new-version` rebase, the first release on the new base resets the build number to `+1`.
