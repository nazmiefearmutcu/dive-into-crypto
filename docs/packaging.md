# Packaging & releases

How the two editions ship. The **Android** edition is built and published by CI from a
`v*` tag; the **desktop** edition is packaged on demand with PyInstaller. All of this is
automated in [`.github/workflows/release.yml`](../.github/workflows/release.yml); the
per-push gates live in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## Android releases (tag → signed APK)

1. Get a `v*` tag onto GitHub (see [the push caveat](#this-machine-git-push-over-1-mb-is-broken)
   below for how that works on the owner's machine).
2. The `release` job in `release.yml` then:
   - decodes the `KEYSTORE_BASE64` secret to `android/app/release.keystore.jks`,
   - exports the signing env the Gradle scaffold already reads — `STORE_FILE`,
     `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see the signing block in
     [`android/app/build.gradle.kts`](../android/app/build.gradle.kts)),
   - runs `./gradlew :app:assembleRelease :app:bundleRelease` (R8-minified),
   - creates the GitHub Release for the tag and uploads the `.apk` and `.aab` from
     `android/app/build/outputs/{apk,bundle}/release/`.

### Signing secrets

| Secret | Meaning | Notes |
| --- | --- | --- |
| `KEYSTORE_BASE64` | base64 of `release.keystore.jks` | decoded by CI to `android/app/release.keystore.jks` |
| `KEYSTORE_PASSWORD` | keystore store password | mapped to Gradle's `STORE_PASSWORD` |
| `KEY_ALIAS` | signing key alias | mapped to Gradle's `KEY_ALIAS` |
| `KEY_PASSWORD` | signing key password | mapped to Gradle's `KEY_PASSWORD` |

Defaults & graceful degradation: `STORE_FILE` always points at `app/release.keystore.jks`
— the path CI decodes to, relative to `android/`. If any of the four values is blank
(e.g. the secrets are simply not configured), the Gradle scaffold skips the signing
config and CI **still publishes the release with an unsigned APK/AAB**, preceded by a
`::warning::no keystore secret set; APK will be unsigned` annotation. Set all four
secrets together.

### Generating a keystore locally

```bash
keytool -genkeypair -v \
  -keystore release.keystore.jks -alias dive \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep the file and both passwords safe — they are your update-signing identity; losing
them means you can never ship an update under the same application signature. Never
commit it: `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties` on
purpose.

Base64-encode it for the `KEYSTORE_BASE64` secret:

```bash
# Git Bash / Linux
base64 -w0 release.keystore.jks > keystore.b64
```

```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore.jks")) | Set-Content -NoNewline keystore.b64
```

## Desktop packaging (PyInstaller)

From `desktop/backend`:

```bash
uv sync
uv run --with pyinstaller pyinstaller dive.spec --noconfirm
```

- Produces **`dist/dive-desktop/`**, an *onedir* bundle — start it with
  `dist/dive-desktop/dive-desktop.exe` (serves `127.0.0.1:8780` and opens the UI).
- **Why not onefile:** single-exe self-extractors are a classic antivirus
  false-positive trigger and pay a temp-dir extraction cost on every launch. The onedir
  layout avoids both.
- The spec bundles the committed prebuilt UI (`desktop/ui/dist`) so the frozen app can
  serve it. It is placed twice on purpose: at `_internal/ui/dist` (`sys._MEIPASS/ui/dist`,
  used by the frozen-build fallback in `api/app.py`) and — via the packaging job's mirror
  step — at `<dist-root>/ui/dist`, which is where `api/app.py`'s original `_UI_DIST` path
  math (`parents[4]/ui/dist`) resolves inside a frozen tree. Extract/release the whole
  `dist/` tree together and both resolvers work. See the `dive.spec` header for the full
  path math.
- **Size honesty: expect roughly 150–250 MB unzipped.** pandas + numpy + FastAPI/uvicorn
  dominate; that is the price of shipping the full reference engine, not a packaging bug.
  The zip the packaging job uploads is smaller, but still comfortably in the
  three-digit-MB range.
- Dependencies come from the locked env — see
  [`desktop/backend/requirements-freeze.md`](../desktop/backend/requirements-freeze.md).

Manual runs: GitHub → **Actions → Release → Run workflow** → tick `package_desktop`. It
runs on `windows-latest` and uploads `dive-desktop-windows-x64.zip` as a workflow
artifact. It never runs on tags.

## This machine: `git push` over 1 MB is broken

On the owner's machine `git push` fails for transfers larger than ~1 MB, which most
release pushes are (a tag push carries any not-yet-pushed commits under it). Releases
must therefore be creatable **via the REST API or CI**, not a local push:

- Create the tag on GitHub without pushing:
  `POST /repos/{owner}/{repo}/git/refs` with
  `{"ref": "refs/tags/v0.x.y", "sha": "<commit sha already on GitHub>"}` — the `push`
  event fires and `release.yml` takes over.
- Or create tag + release in one call:
  `gh release create v0.x.y --target <branch-or-sha> --title ... --notes ...` creates
  the tag server-side; the workflow then attaches the built artefacts.

In both cases the commits themselves must have reached GitHub first via the API ship
path. The `release.yml` workflow creates the GitHub Release through the preinstalled
`gh` CLI (a GitHub REST API wrapper) — no local `git push` involved.
