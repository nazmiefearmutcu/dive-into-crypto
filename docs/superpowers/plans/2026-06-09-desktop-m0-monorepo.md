# M0 — Monorepo Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: this milestone is git-history surgery and is executed **inline by the lead** (not delegated to subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the Android-only repo into a monorepo (`/android` + future `/desktop`) without breaking the Android build.

**Architecture:** `git mv` the entire Gradle project into `android/` as a block (paths are project-relative, so they survive), move Android-scoped docs with it, install a small root README hub stub, and verify the Android app still configures, builds, and tests under JDK 17.

**Tech Stack:** git, Gradle, JDK 17 (`/opt/homebrew/opt/openjdk@17`), Android SDK (`~/Library/Android/sdk`).

**Spec:** `docs/superpowers/specs/2026-06-09-dive-into-crypto-desktop-design.md` §10.

---

### Task 1: Move the Android project into `android/`

**Files:**
- Move: `app/ gradle/ gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties .editorconfig BENCHMARKS.md CHANGELOG.md` → `android/`
- Move: `README.md` → `android/README.md`
- Keep at root: `LICENSE .gitignore docs/`

- [ ] **Step 1: Create the android dir and move tracked files with git mv**

```bash
cd ~/dive-into-crypto
mkdir -p android
git mv app gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts \
       gradle.properties .editorconfig BENCHMARKS.md CHANGELOG.md android/
git mv README.md android/README.md
```

- [ ] **Step 2: Verify the move (root is clean, android holds the project)**

Run: `ls -A ~/dive-into-crypto && echo '---' && ls -A ~/dive-into-crypto/android`
Expected: root shows `LICENSE .gitignore docs android .git`; `android/` shows `app gradle gradlew … settings.gradle.kts README.md BENCHMARKS.md CHANGELOG.md .editorconfig`.

---

### Task 2: Root README hub stub + monorepo .gitignore

**Files:**
- Create: `README.md` (root hub stub — finalized in M4)
- Modify: `.gitignore` (add `/desktop` build artifacts; confirm Android patterns still match under `android/`)

- [ ] **Step 1: Confirm gitignore patterns are path-agnostic**

Run: `cat ~/dive-into-crypto/.gitignore`
Expected: patterns like `build/`, `.gradle/`, `local.properties`, `*.apk` with NO leading `/` (so they match under `android/` too). If any pattern is anchored with a leading slash (e.g. `/build`), de-anchor it (remove the leading slash) so it matches `android/build` as well.

- [ ] **Step 2: Append desktop ignores to .gitignore**

Append:
```gitignore

# ── Desktop edition ──
desktop/ui/dist/
desktop/ui/node_modules/
desktop/backend/.venv/
desktop/backend/**/__pycache__/
desktop/**/*.pyc
desktop/backend/data/
.DS_Store
```

- [ ] **Step 3: Write the root README hub stub**

```markdown
# Dive Into Crypto

A financial scanner for **Binance USDT‑M perpetual futures**: 15 technical indicators
across 12 timeframes, cross‑checked against whale (top‑trader) positioning, collapsed into
one confidence‑scored consensus verdict per symbol. It reads only **public** market data —
no account, no API keys. It is an analysis tool, **not financial advice** and **not an
automated trader**.

## Editions

| Edition | Stack | Data | Status |
| --- | --- | --- | --- |
| **[Android](android/)** | Kotlin · Jetpack Compose | Binance USDT‑M public REST + WS | Released — `v0.1.0` |
| **[Desktop](desktop/)** | Python (Crypcodile) + React terminal UI | Crypcodile‑fed, highest‑fidelity | In development |

Both editions share the same consensus engine (15 indicators · 12 timeframes ·
whale‑divergence filtering). See each edition's README for details.

## License

[MIT](LICENSE) © nazmiefearmutcu
```

- [ ] **Step 4: Verify**

Run: `ls ~/dive-into-crypto/README.md && head -3 ~/dive-into-crypto/README.md`
Expected: root README exists, title `# Dive Into Crypto`.

---

### Task 3: Verify the Android build still works under JDK 17

**Files:**
- Create (local, gitignored): `android/local.properties` with `sdk.dir`

- [ ] **Step 1: Point Gradle at the Android SDK**

```bash
cd ~/dive-into-crypto/android
printf 'sdk.dir=%s/Library/Android/sdk\n' "$HOME" > local.properties
```

- [ ] **Step 2: Configure the project with JDK 17 (cheap sanity check first)**

Run:
```bash
cd ~/dive-into-crypto/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:tasks --console=plain -q 2>&1 | tail -20
```
Expected: Gradle configures successfully and prints the task list (no "settings file not found" / path errors). This proves the block-move kept all paths valid.

- [ ] **Step 3: Build the debug APK**

Run:
```bash
cd ~/dive-into-crypto/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug --console=plain 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`. (First run downloads the Gradle distribution + dependencies — may take several minutes.)

- [ ] **Step 4: Run the unit + fixture tests**

Run:
```bash
cd ~/dive-into-crypto/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`; the 61 indicator/consensus fixture tests pass. If the toolchain cannot complete (e.g. missing SDK platform), record the exact failure and resolve the SDK/component before proceeding — do not mark M0 done on an unverified build.

---

### Task 4: Commit the restructure

- [ ] **Step 1: Stage and commit**

```bash
cd ~/dive-into-crypto
git add -A
git commit -m "refactor(repo): restructure into monorepo (android/ + desktop/)

Move the Android Gradle project under android/ verbatim; add a root README
hub stub covering both editions. Android build/paths unchanged."
```

- [ ] **Step 2: Verify clean tree + no attribution trailer**

Run: `cd ~/dive-into-crypto && git status --short && git log -1 --format='%an <%ae>'`
Expected: empty status (clean), author `nazmiefearmutcu <unlosttheunbertea@gmail.com>`, no `Co-Authored-By` lines.

---

## Self-Review

- **Spec coverage (§10):** Task 1 = block move; Task 2 = root README hub + gitignore; Task 3 = Android-still-builds verification; Task 4 = branch commit (no force-push). ✓
- **Placeholder scan:** none — every step has exact commands. ✓
- **Type/path consistency:** moved set matches the spec's §4.1 layout; root keeps `LICENSE .gitignore docs`. ✓
- **Working software:** after M0 the repo is a valid monorepo with a buildable Android edition. ✓
