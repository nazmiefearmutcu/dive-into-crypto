# Google Play release checklist: DVCRY (Dive into Crypto) (owner)

Package: `com.diveintocrypto.android` · versionName 0.4.0 · versionCode 4 (current value in `android/app/build.gradle.kts`) · minSdk 26 · targetSdk 36 · compileSdk 36
Prepared: 2026-11-08. Work the steps in order. Items marked **verify** are not confirmed by the official pages this kit cites, so check them in Play Console before acting.

Companion files: `docs/privacy-policy.md` and `docs/privacy-policy.html` (policy text), `docs/play-store/listing-tr.md` and `listing-en.md` (store text), `docs/play-store/data-safety.md` (Data safety answers).

## Phase A: Account, identity and package registration

- [ ] **A1. Complete Play Console identity verification.** Play requires developer identity verification and package registration for all Play packages from 2026-09-30. Apps that are not registered will be removed from Play. The official page does not list the enforcement countries, and the $25 fee comes only from secondary sources, so **verify** both in Console. Source: https://support.google.com/googleplay/android-developer/answer/16984799?hl=en
- [ ] **A2. Create the app in Play Console with package `com.diveintocrypto.android`.** Confirm the registration status on the Console home page before the first upload. The package name cannot be changed later.
- [ ] **A3. Record the account type and creation date.** Personal accounts created after 2023-11-13 must complete the closed-test requirement in Phase G before production access. The official page names no organization exemption. Third-party sources claim one, so **verify** in Console and do not rely on it. Source: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- [ ] **A4. Choose the default store listing language** (tr-TR or en-US). Both listings are provided. The in-app language default for non-Turkish devices is a separate app decision (see Owner decisions at the end).
- [ ] **A5. Check the target API status.** New apps and updates must target API 36 from 2026-08-31. The project already sets `compileSdk = 36` and `targetSdk = 36`. Check Console for any extension that was granted, because the optional extension to 2026-11-01 has passed. Source: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en and https://developer.android.com/google/play/requirements/target-sdk

## Phase B: Upload key, Play App Signing and secrets

- [ ] **B1. Create the upload key outside the repository.** Run in PowerShell. The key file must never be committed. Use an ASCII-only folder: a non-ASCII user profile path breaks Gradle (see the Windows build notes in `docs/testing.md`).

```powershell
New-Item -ItemType Directory -Force "C:\dive-secure" | Out-Null
keytool -genkeypair -v -storetype PKCS12 -keystore "C:\dive-secure\dive-upload.jks" -alias dive-upload -keyalg RSA -keysize 2048 -validity 10000
```

- [ ] **B2. Back up the key and both passwords** in a password manager and in one offline encrypted copy. If the upload key is lost, the Play upload-key reset process applies. **Verify** the current process in Play Console Help.
- [ ] **B3. Add the four GitHub Actions secrets** at GitHub > repository Settings > Secrets and variables > Actions. Use these exact names, which the release job in `.github/workflows/release.yml` reads from its signing and verification steps:
  - `KEYSTORE_BASE64`: the base64 of the `.jks` file. Create it with:
    ```powershell
    [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\dive-secure\dive-upload.jks")) | Set-Clipboard
    ```
  - `KEYSTORE_PASSWORD`: the keystore password.
  - `KEY_ALIAS`: `dive-upload` (or the alias you chose).
  - `KEY_PASSWORD`: the key password.
  The release job fails when any of the four is missing (`release.yml`), so an unsigned bundle cannot be published by the tag build.
- [ ] **B4. Optional local signing.** For a local build, create `android\keystore.properties`. It is git-ignored. The Gradle file reads the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword` (the `keystorePropsFile` and `signingValue` reads near the top of `android/app/build.gradle.kts`). Use an absolute path for `storeFile`. `.gitignore` covers `*.keystore`, `*.jks`, `*.p12`, `*.pfx` and `keystore.properties`, but keep the key file outside the repository anyway.
- [ ] **B5. Enrol in Play App Signing** when you create the first release. Google holds the app signing key, and the AAB is signed with the upload key from B1. Confirm the enrolment screen in Console. **Verify** the current Play App Signing help page in Console.
- [ ] **B6. Apply the version code rule.** Every upload to Play needs a versionCode higher than every earlier upload for this package, on any track. The current value is `versionCode = 4` (the `versionCode` in `defaultConfig` of `android/app/build.gradle.kts`). The GitHub v0.3.0 build used code 3. If any AAB with code 4 has already reached Play (internal, closed or production), set the next code (5 or higher) before you build. Confirm the upload history in Console first. Never reuse a code. Later, read the code from the CI run number so it always increases.

## Phase C: Build and verify the release bundle

- [ ] **C1. Build the signed bundle** (the owner runs Gradle; the CI release job runs the same tasks on `v*` tags). On Windows, read the Windows build notes in `docs/testing.md` first if your user profile path is non-ASCII.

```powershell
cd C:\dive-into-crypto\android
.\gradlew.bat :app:test :app:bundleRelease
```

  Output: `C:\dive-into-crypto\android\app\build\outputs\bundle\release\app-release.aab`. Keep `C:\dive-into-crypto\android\app\build\outputs\mapping\release\mapping.txt` with the release, because Console needs it to read stack traces from the minified build.

- [ ] **C2. Verify the AAB signature** with the JDK tool (expect "jar verified"; the `-certs` output must show your upload key):

```powershell
jarsigner -verify -verbose -certs "C:\dive-into-crypto\android\app\build\outputs\bundle\release\app-release.aab"
```

- [x] **C3. Check 16 KB page-size compatibility** (Play requires it for apps that target API 35 and above on 64-bit devices; updates that do not support it cannot be released from 2027-02-01). **Verified 2026-11-08 on the 0.4.0 release bundle:** the only native library is `libandroidx.graphics.path.so` (pulled in by Compose) and all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) have every ELF `PT_LOAD` segment aligned to 16384 bytes; the app ships no native code of its own. Re-run the check with the official method at https://developer.android.com/guide/practices/page-sizes, or the bundletool command below, whenever a dependency changes:

```powershell
bundletool dump config --bundle="C:\dive-into-crypto\android\app\build\outputs\bundle\release\app-release.aab" | findstr /i alignment
```

  Re-check after every dependency upgrade (versions are pinned in `android/gradle/libs.versions.toml`).

- [ ] **C4. Check the merged release manifest.** Confirm the permissions are only `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS`, plus the app's own internal permissions. Confirm there is no `com.google.android.gms.permission.AD_ID` entry. Search the merged manifest under `android\app\build\intermediates`.
- [ ] **C5. Smoke-test the release build on a real device or emulator.** Install it and open Panel, Scanner, Alarms, Settings and the home-screen widget. Test the "Clear all local data" action (the app restarts itself after a successful clear) and the Privacy policy and Binance affiliation lines on the About screen. Test back navigation on Android 16 (predictive back).

## Phase D: Store settings and declarations

- [ ] **D1. Enter the developer contact email** in Store settings: `<OWNER: support email>`. Put the same address in `SUPPORT_EMAIL` in `android/app/src/commonMain/kotlin/com/diveintocrypto/android/ui/settings/AppLinks.kt`. It is empty now, so the Contact row in Settings stays hidden until it is set.
- [ ] **D2. Publish the privacy policy at a public URL.** The policy must be reachable without login and not geofenced. Replace the `<OWNER: ...>` placeholders in `docs/privacy-policy.md` and `docs/privacy-policy.html` first (developer name, support email, Binance privacy policy URL). Suggested host: GitHub Pages from the `docs` folder. Enable it under repository Settings > Pages. The URL then follows the pattern `https://<owner>.github.io/<repo>/privacy-policy.html`. **Verify** the URL after enabling. Then set `PRIVACY_POLICY_URL` in the same `AppLinks.kt` file to that URL, so the "open online" policy link appears in Settings. It is empty now, so the link stays hidden. GitHub Pages on a private repository needs a paid plan. Enter the URL in Console (App content > Privacy policy) and in the listings.
- [ ] **D3. Complete the Data safety form** from `docs/play-store/data-safety.md`. Answer "No data collected" and "No data shared" unless you decide otherwise on the IP-address judgment point in that file. Re-check the form if the backup rules change.
- [ ] **D4. Complete the Financial features declaration** in Console. File it after the wording changes that remove "Should Trade", `OPEN_*` action tokens, and position-size or leverage text are merged, because the declaration must describe the shipped app. Answers to give (**verify the exact Console wording**):
  - Does the app offer investing, trading, lending, payments, insurance, a crypto exchange or wallet, or custody of money or crypto? **No.**
  - Does the app give personalised financial advice? **No.** The app shows market analytics and states that it is not investment advice.
  - Does the app place orders or connect to an exchange account? **No.** It reads public Binance market data only.
  - Does the app hold user funds or crypto? **No.**
  - Describe it as: analytics and research for public crypto-futures market data, with no account and no order placement.
  - The Financial Services policy scope covers "management or investment of money and cryptocurrencies, including personalized advice", and the crypto exchange and wallet policy applies to apps that handle crypto. Consider a Play policy inquiry before submission. Sources: https://support.google.com/googleplay/android-developer/answer/18258653?hl=en and https://support.google.com/googleplay/android-developer/answer/9876821?hl=en. The page says the update is effective 2026-10-29 unless otherwise stated.
- [ ] **D5. Choose the app category** that matches the content, and declare the financial features truthfully whichever category you pick. A Finance category means the Financial Services rules apply in full.
- [ ] **D6. Complete the content rating (IARC questionnaire).** Expected answers (**verify** each in Console): violence none; sexual content none; profanity none; controlled substances none; gambling none (the app has no betting, prizes or real-money mechanics); user-generated content no; sharing of location no; sharing of personal info no; digital purchases no; ads no.
- [ ] **D7. Set the target audience to adults (18 and over)** and state that the app is not designed for children. Answer "Contains ads: No". The Play Families policy would apply only if you ever targeted children, which this app does not.
- [ ] **D8. Complete the app access section.** Say that no login or special access is needed, and give the reviewer notes below. Attach a screen recording with live data, made on the release build.

  Reviewer notes (English, paste into Play Console "Notes for reviewers"):
  > DVCRY is an analytics app that displays public Binance USDT-M futures market data. It has no accounts, no logins, no purchases, and it does not place orders or hold funds. The app needs access to Binance public market-data servers (fapi.binance.com, api.binance.com, fstream.binance.com). If Binance restricts access from your location, the app shows an error message and no market data. A screen recording with live data is attached. Contact: <OWNER: support email>.

- [ ] **D9. Set country availability.** Binance restricts API access in some countries. Exclude the United States at a minimum, and exclude any other country where Binance does not serve these public endpoints. Binance's restricted-jurisdictions list was **not** verified in this review, so check Binance's current published list and test a device in each candidate country before you choose it. Note that a restricted region can see HTTP 451 errors from Binance.

## Phase E: Store listing and graphics

- [ ] **E1. Paste the store text** from `docs/play-store/listing-tr.md` and `listing-en.md`. Replace the `<OWNER: ...>` placeholders (privacy URL, support email) first. Limits: app name 30 characters, short description 80, full description 4000. Measured counts are in each file (app name 16; short descriptions 79 TR and 68 EN; full descriptions 1790 TR and 1679 EN before placeholders are replaced).
- [ ] **E2. App icon: 512 x 512 px 32-bit PNG with alpha.** No 512 px icon exists in the repository; export one from the launcher vector art (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`) or from the design source. No Binance logo, mark or colour scheme that implies affiliation.
- [ ] **E3. Feature graphic: 1024 x 500 px**, JPEG or 24-bit PNG with no alpha. Keep Binance marks out.
- [ ] **E4. Phone screenshots: at least 2 per language (TR and EN)**, captured from the release build on a device or emulator. Suggested set: Scanner list, Panel detail, Alarms, home-screen widget, Settings. Mark sample data as sample, and do not use third-party logos. Do not use the desktop PNGs in `docs/screenshots` (2560 x 1440). Before capturing, make sure no screen shows `OPEN_` action tokens, "Should Trade", position-size or leverage text. Suggested size 1080 x 2400, within the Console's accepted range (**verify**).
- [ ] **E5. Tablet screenshots (7-inch and 10-inch)** are optional but recommended for the 840 dp two-pane layout.
- [ ] **E6. Optional promo video** (YouTube URL).
- [ ] **E7. Check the trademark statement.** Confirm the affiliation sentence appears in both listings and in the About screen. Check Binance's brand terms before publishing, because the report did not verify them.

## Phase F: Upload and review

- [ ] **F1. Upload the AAB** to a closed testing track first, not to production.
- [ ] **F2. Review the pre-launch report** (Console > Testing > Pre-launch report) after processing. Check crashes, ANRs, accessibility and security findings on the minified build. Fix any crash that appears on the minified release before you go further.
- [ ] **F3. Check Android vitals and the Console warnings** on the closed track. Confirm that the 16 KB and target API warnings are gone.
- [ ] **F4. Run the release once more on Android 15 and 16** with gesture navigation and three-button navigation (edge-to-edge bars, predictive back).

## Phase G: Closed test requirement (personal accounts)

- [ ] **G1. Recruit at least 12 testers, and 14 or more for a safety margin.** They must be opted in to the closed track continuously for at least 14 consecutive days. Keep at least 12 opted in at the moment you request production access.
- [ ] **G2. Plan at least 3 weeks** between the start of the closed test and public launch. Source: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- [ ] **G3. After 14 consecutive days**, apply for production access in Console.

## Phase H: Production and after launch

- [ ] **H1. Create the production release** with the same signed AAB (or a newer one with a higher versionCode) and start a staged rollout. Monitor Android vitals at each stage.
- [ ] **H2. Keep the privacy policy, listing and Data safety answers in step.** Any new SDK, permission, host or backup change requires an update to all three.
- [ ] **H3. Monitor reviews and support email.** Keep the reviewer notes and the country list current.

## Housekeeping (owner, not blocking)

- [ ] Delete or replace the debug APK asset on the GitHub v0.3.0 release (`dive-into-crypto-v0.3.0-debug.apk`), which is not a Play build and cannot upgrade in place.
- [ ] Confirm the Play upload history before you choose the versionCode.
- [ ] Check Console for any Target API extension that was granted.
- [ ] Do not run Google Ads for this app without a policy review. Google Ads prohibits "cryptocurrency trading signals" and "cryptocurrency investment advice" destinations, so keep any ad copy to analytics and research wording and avoid the word "signal".

## Owner decisions still open

1. Default language for devices that are not Turkish. The recommendation is English.
2. Whether the portfolio and alert data stays out of Auto Backup, as the current `backup_rules.xml` and `data_extraction_rules.xml` provide. The policy and the Data safety answers assume it does.
3. The support email address and the developer name for the policy and the listing.
4. The privacy policy hosting location.
5. Whether the default scan universe `ALL` stays the default.
6. The IP-address judgment point in the Data safety file.

## Official sources cited in this kit

- Developer verification and package registration: https://support.google.com/googleplay/android-developer/answer/16984799?hl=en
- Target API requirement: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en and https://developer.android.com/google/play/requirements/target-sdk
- Closed test requirement for personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- Privacy policy requirement: https://support.google.com/googleplay/android-developer/answer/9888076?hl=en
- Financial Services policy: https://support.google.com/googleplay/android-developer/answer/9876821?hl=en
- Crypto exchange and wallet policy: https://support.google.com/googleplay/android-developer/answer/18258653?hl=en
- Impersonation policy: https://support.google.com/googleplay/android-developer/answer/9888374?hl=en
- 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Android 15 behaviour changes: https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 behaviour changes: https://developer.android.com/about/versions/16/behavior-changes-16
- Google Ads prohibited destinations (only if you ever advertise): https://support.google.com/adspolicy/answer/14009787?hl=en
- Store graphics requirements: Play Help answer 1078870 (taken from an earlier fetch; **verify** the current limits in Console)

Not verified in this review: Binance's restricted-jurisdictions list and whether HTTP 451 applies to every US reviewer; AGP 8.11 with Kotlin 2.0.21 and Compose Multiplatform 1.7.3 (the owner's build verifies this); the exact Console wording for the Financial features declaration; any Target API extension status in Console.
