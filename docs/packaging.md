# Packaging & releases

How the two editions ship. The **Android** edition is built as a signed Play bundle (AAB) and,
on a `v*` tag, published by CI. The **desktop** edition is packaged on demand with PyInstaller.
Both are automated in [`.github/workflows/release.yml`](../.github/workflows/release.yml); the
per-push gates live in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

The step-by-step Google Play process (console forms, upload, review, closed test) is in
[`docs/play-store/release-checklist.md`](play-store/release-checklist.md). This page covers the
build and signing side.

## Android: the Play path (signed AAB)

Google Play takes the **AAB** built by `bundleRelease`. Release tasks are signed or they fail:
the signing configuration is read in `android/app/build.gradle.kts`, and a release task without
a complete signing configuration stops with a message that names the missing values.

### Signing values

Provide the same four values either way; environment variables take precedence, and a blank
value counts as unset.

| Environment variable | `android/keystore.properties` key | Meaning |
| --- | --- | --- |
| `STORE_FILE` | `storeFile` | path to the keystore; relative to `android/`, or absolute |
| `STORE_PASSWORD` | `storePassword` | keystore password |
| `KEY_ALIAS` | `keyAlias` | key alias inside the keystore |
| `KEY_PASSWORD` | `keyPassword` | key password |

`keystore.properties` lives next to `settings.gradle.kts` in `android/` and is git-ignored. Do not
commit it, and do not commit the keystore itself.

### Build the bundle locally

```powershell
cd android
.\gradlew.bat :app:test :app:bundleRelease
```

Output:

- `android/app/build/outputs/bundle/release/app-release.aab` — upload this one to Play.
- `android/app/build/outputs/mapping/release/mapping.txt` — keep it with the release; Play uses it
  to deobfuscate crash reports for this build.

Check the signature before you upload (`jarsigner -verify -verbose -certs <aab>` must report
`jar verified` with your upload key; see step C2 of the checklist).

`-PallowUnsignedRelease=true` exists only for CI's unsigned smoke build (`ci.yml`, which builds
the bundle to prove it compiles and minifies). Never distribute an unsigned output.

### Tag release in CI

Pushing a `v*` tag runs the `release` job in `release.yml`, which:

- checks that all four signing secrets are set and **fails before building if any is missing**;
  an unsigned release is never built or published by CI,
- decodes the `KEYSTORE_BASE64` secret to `android/app/release.keystore.jks`,
- exports `STORE_FILE=app/release.keystore.jks`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
  from the secrets,
- runs `./gradlew :app:test :app:assembleRelease :app:bundleRelease` (R8-minified),
- verifies the signatures of the built artifacts (`jarsigner` for the AAB, `apksigner` for the APK),
- creates the GitHub Release for the tag and attaches three files: the `.aab` (Play bundle), the
  `.apk` (the same build, signed, sideloadable) and `mapping.txt`.

A manual run of the **Release** workflow with `android_bundle` ticked performs the same signed
build and verification, uploads the AAB, APK and mapping as a workflow artifact for 14 days, and
creates **no** tag or GitHub Release.

### Signing secrets

| Secret | Meaning | Notes |
| --- | --- | --- |
| `KEYSTORE_BASE64` | base64 of `release.keystore.jks` | decoded by CI to `android/app/release.keystore.jks` |
| `KEYSTORE_PASSWORD` | keystore store password | mapped to Gradle's `STORE_PASSWORD` |
| `KEY_ALIAS` | signing key alias | mapped to Gradle's `KEY_ALIAS` |
| `KEY_PASSWORD` | signing key password | mapped to Gradle's `KEY_PASSWORD` |

All four must be set together. If a value is missing, `release.yml` stops before the build starts.

### Generating a keystore

```powershell
keytool -genkeypair -v -storetype PKCS12 -keystore release.keystore.jks -alias dive `
  -keyalg RSA -keysize 2048 -validity 10000
```

Use an ASCII-only folder for the keystore and its base64 copy (see the Windows build notes in
[`docs/testing.md`](testing.md#windows-build-notes)). Keep the file and both passwords in a
password manager and in an offline backup. Never commit it: `.gitignore` blocks `*.jks`,
`*.p12`, `*.pfx`, `*.keystore` and `keystore.properties` on purpose.

**What losing this key means depends on how the app is distributed:**

- **Google Play with Play App Signing (recommended):** Google holds the *app signing key*
  and this keystore is only the *upload key*. A lost or compromised upload key can be reset
  through Play Console support; check the current Play Help article on upload-key reset
  before you rely on it.
- **Sideload only, with no Play App Signing:** this key *is* the app's identity. If it is
  lost, no future update can install over an existing copy.

The APK attached to a GitHub Release is signed with this key. Play re-signs what it distributes
with the app signing key, so an APK installed from GitHub and the Play-installed copy **cannot
update each other**. Pick one channel per device when you test.

### Google Play rules that the pipeline depends on

- **Version code.** Every upload to Play needs a `versionCode` higher than every code previously
  uploaded to that app, on any track, including internal testing. `android/app/build.gradle.kts`
  holds `versionCode` and `versionName` by hand. Increase `versionCode` for every upload, even if
  `versionName` stays the same, and check the Play Console release history before you tag.
  Reading the code from the CI run number would make this automatic; that has not been set up.
- **Play App Signing.** Enrol when you create the app or on the first upload. The keystore above
  is the upload key.
- **Start on the internal testing track.** Upload the `.aab`, then promote it only after testing.
- **Upload `mapping.txt`** for the same release so crash stack traces are readable in Console.
- **Privacy policy and support contact.** The policy text is shown in the app (Settings → About →
  privacy). The public URL is `PRIVACY_POLICY_URL` and the contact address is `SUPPORT_EMAIL`, both in
  `android/app/src/commonMain/kotlin/com/diveintocrypto/android/ui/settings/AppLinks.kt`. Both are
  empty until the owner fills them in, and the rows that use them stay hidden while they are empty.
- **Data safety and the store listing** are Play Console forms, not repo files. They must match the
  app: public Binance market data only (the Android app does not contact Deribit), and preferences,
  watchlists and alerts kept on the device. See [`docs/play-store/`](play-store/).

Base64-encode the keystore for the `KEYSTORE_BASE64` secret:

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
- The spec bundles the committed prebuilt UI (`desktop/ui/dist`) under `sys._MEIPASS/ui/dist`
  (`_internal/ui/dist` in the onedir folder). When frozen, `api/app.py` serves the UI from there.
  The UI ships once; no copy step is needed.
- **Size honesty: expect roughly 150–250 MB unzipped.** pandas + numpy + FastAPI/uvicorn
  dominate; that is the price of shipping the full reference engine, not a packaging bug.
  The zip the packaging job uploads is smaller, but still comfortably in the
  three-digit-MB range.
- Dependencies come from the locked env — see
  [`desktop/backend/requirements-freeze.md`](../desktop/backend/requirements-freeze.md).
- **Smoke check before you hand a build out.** Start `dist/dive-desktop/dive-desktop.exe`, then
  request `http://127.0.0.1:8780/api/symbol/BTCUSDT`. A 200 response with symbol data means
  the engine works in the frozen build. A 502 `symbol_fetch_failed` means a bundled resource
  is missing (for example `engine/config/default.yaml`, which `dive.spec` must include). The
  packaging job does not run this check yet, so run it by hand.

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
