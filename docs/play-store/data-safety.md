# Google Play Data safety: answers for DVCRY (Dive into Crypto)

Applies to: Android app `com.diveintocrypto.android`, versionName 0.4.0, versionCode 4 (`android/app/build.gradle.kts`), as built from this repository on 2026-11-08.
Official guidance: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en (Data safety section help).

The Console wording and question order can change. Use this file as the source of each answer, and confirm the exact labels in Play Console before you submit. Re-check this file before every release, because a new SDK or a change to the backup rules can change the answers.

## 0. Facts the answers rely on

| Fact | Evidence in the repository |
| --- | --- |
| No developer-operated server. The app contacts only Binance public endpoints. | Base URLs: `https://fapi.binance.com` (`data/binance/BinanceFuturesClient.kt`), `https://api.binance.com` (`BinanceSpotClient.kt`), `wss://fstream.binance.com` (`data/binance/BinanceWsClient.kt`, `engine/LiveTickerEngine.kt`), `wss://stream.binance.com:9443` (spot WebSocket, chosen in Settings). |
| Deribit is not contacted by any app screen. | `DeribitConnector` is constructed by default in `MarketDataEngine.kt`, but `deribitRecords`, `optionChainStream` and `derivativeTickerStream` have no callers outside tests. Do not list Deribit. Re-check if an options screen ships. |
| No analytics, advertising, crash-reporting, attribution or billing SDK. | `gradle/libs.versions.toml` and `app/build.gradle.kts` contain only JetBrains and AndroidX libraries, Ktor, kotlinx, Material, WorkManager and Glance. No Firebase, Play Services, Crashlytics, AdMob, billing, Sentry or similar. |
| No device or advertising identifier is read; no custom HTTP headers are set. | Grep finds no `ANDROID_ID`, `AdvertisingIdClient`, `getAdvertisingIdInfo`, `Settings.Secure`, `Build.MODEL`, `Build.FINGERPRINT` or custom `header(` calls. |
| No account, login, API key or request signing. | No signing or `X-MBX` code paths in `commonMain` or `androidMain`. |
| Cleartext traffic is disabled. | `AndroidManifest.xml` (`usesCleartextTraffic="false"`), `res/xml/network_security_config.xml` (`cleartextTrafficPermitted="false"`). |
| Settings, portfolio, alerts, evidence and widget state stay on the device and are excluded from backup and device transfer. | `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude `dive_settings.xml` and `dive_permissions.xml`. Portfolio entries, alert rules, fired-alert history and the evidence log are stored under the `dive_settings` preference file (`data/AndroidKeyValueStore.kt`). |
| Share-card PNGs are written to the app cache. | `share/ShareCard.kt` writes to `cacheDir/share_cards/`; `res/xml/file_paths.xml` exposes that path through FileProvider. |
| The network log (Logs screen) is in memory only. | `data/binance/NetworkLog.kt` keeps a 200-entry in-memory ring buffer; the Logs screen reads it. |
| The app has an in-app "Clear all local data" action. | `ui/settings/SettingsScreen.kt` and `platform/ExternalActions.android.kt` clear the `dive_settings` preferences (which include the widget state) and the share-card cache, stop background scans, and restart the app. The notification-prompt flag in `dive_permissions` is kept on purpose. |

Permissions declared in `AndroidManifest.xml`: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (runtime, optional, Android 13 and later). No location, contacts, camera, microphone, storage, photos, phone, SMS, calendar, health or ad-ID permission is declared.

## 1. Top-level questions

| Question | Answer | Justification |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | Play counts data as "collected" only when it is transmitted off the device by the app, and data that only stays on the device does not have to be declared (Data safety help, linked at the top of this file). The app sends public market-data requests (symbol, interval, limit) to Binance. Those requests contain no user data. Portfolio, alerts, evidence and settings never leave the device. The developer has no server. See the judgment point in section 4 about IP addresses. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | All endpoints are HTTPS or WSS; cleartext traffic is disabled (section 0). |
| Do you provide a way for users to request that their data is deleted? | **Yes** (on-device deletion) | No data is held by the developer. Users delete everything in the app: Settings > "Clear all local data" (Turkish label "Tüm yerel verileri temizle"), or Android Settings > Apps > DVCRY > Storage > Clear storage, or by uninstalling. If the form asks for a deletion contact, enter `<OWNER: support email>` and point to section 8 of the privacy policy. |

Note: if the Console asks about data a user deletes "from your servers", answer that the app has no developer servers and that deletion is done on the device as described above.

## 2. Data types

Answer "Not collected" for every row. The rows below explain the reasoning so the owner can check each one.

| Play data type | Answer | Reason |
| --- | --- | --- |
| Location: approximate location | Not collected | No location permission. Binance receives the IP address (section 4), which is not location data collected by the app. |
| Location: precise location | Not collected | No location permission. |
| Personal info: name, email address, user IDs, address, phone number, race and ethnicity, political or religious beliefs, sexual orientation, other info | Not collected | No account, sign-up, profile or contact form. The app has no user ID. |
| Financial info: user payment info, purchase history, credit score | Not collected | No purchases, no payment SDK, no account. |
| Financial info: other financial info (portfolio entries) | Not collected | Portfolio entries (symbol, entry price, size, direction) are stored on the device only and are never transmitted. They are excluded from Android backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`). Play does not require disclosure of data that is only processed on the device. |
| Health and fitness | Not collected | Not in the app. |
| Messages (emails, SMS, chats, other in-app messages) | Not collected | Not in the app. |
| Photos and videos | Not collected | The app does not read the photo library. Share-card PNGs are generated by the app and handed to the app the user picks. |
| Audio files | Not collected | No microphone access. |
| Files and docs | Not collected | No storage access. The app writes its own share-card PNGs to its cache only. |
| Calendar | Not collected | Not in the app. |
| Contacts | Not collected | Not in the app. |
| App activity: app interactions, in-app search history, installed apps, other user-generated content, other actions | Not collected | No analytics. Screen views and taps are not sent anywhere. The network log stays in memory (section 0). |
| Web browsing | Not collected | No browsing history is collected. |
| App info and performance: crash logs, diagnostics, other | Not collected | No crash-reporting or diagnostics SDK. The app uploads nothing. See the note on Android vitals below. |
| Device or other IDs (advertising ID, device ID, other IDs) | Not collected | No identifier is read or sent (section 0). Verify that the merged release manifest contains no `com.google.android.gms.permission.AD_ID` entry before you submit. |

Note on Play-side data: Android vitals and the pre-launch report are data that Google collects about the app on Google's side (only from users who opted in to usage and diagnostics sharing). They are not data the app sends. The form asks about what the app collects. Confirm this with the Console's own wording when you fill in the form.

## 3. Sharing and third-party SDKs

| Question | Answer |
| --- | --- |
| Is any user data shared with third parties? | **No** |
| Third-party SDKs that collect data | **None.** Re-check after any dependency change. |
| Data sent to Binance | Public market-data requests only (section 4). Binance is the data source, and the developer does not share any user data with it. |

## 4. Judgment points for the owner (confirm before submitting)

1. **IP address.** Every internet request carries the device IP address to Binance. The app's own code does not read or transmit it as a data field, and Google's guidance does not require disclosing IP addresses that are inherent to any internet connection. The recommended answer is therefore "No data collected". If you or Play policy support decide that the IP address counts as collected, the conservative option is to declare it as collected for app functionality, not shared, required for app functionality, and processed ephemerally (not stored) if the Console offers that choice. Pick the category the Console lists for IP addresses and verify the wording there. A Play policy inquiry before submission would settle this.
2. **Backup behaviour.** The answers assume the backup exclusions in `backup_rules.xml` and `data_extraction_rules.xml` remain in the release build. If the exclusions are removed, the portfolio, alert and evidence data would leave the device through Google Auto Backup. The answers must then be revised and the privacy policy section 4 updated.
3. **Deribit.** Not contacted by the current app (section 0). Re-check if a Deribit feature ships.
4. **Financial features.** The Data safety answers do not change the Financial features declaration, which is covered in `release-checklist.md`.

## 5. Consistency checks before submission

- The privacy policy URL in the Console matches the published `docs/privacy-policy.html` (section 8 of the policy describes the deletion path, and section 4 describes the backup behaviour).
- The privacy policy host list matches section 2 of the policy and this file (four Binance hosts; no others).
- The app's About screen links to the same policy.
- Changing any answer here also requires a change to the policy, and the reverse.
