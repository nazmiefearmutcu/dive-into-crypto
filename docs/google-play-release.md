# DVCRY: Google Play release roadmap

Checked against Google's documentation on **7 November 2026**. This is a preparation guide; DVCRY has not been submitted to Google Play by this change.

## Related documents

- [`docs/play-store/release-checklist.md`](play-store/release-checklist.md): owner checklist (keystore and CI secrets, Play App Signing, store assets, declarations, region exclusions).
- [`docs/play-store/data-safety.md`](play-store/data-safety.md): prepared Data safety answers.
- [`docs/play-store/listing-en.md`](play-store/listing-en.md) and [`docs/play-store/listing-tr.md`](play-store/listing-tr.md): full listing texts (EN/TR) that mirror the `fastlane` title and short description.
- [`docs/privacy-policy.md`](privacy-policy.md) / [`docs/privacy-policy.html`](privacy-policy.html): privacy policy draft (TR/EN).

## Naming

| Surface | Name |
| --- | --- |
| Project / README | Dive into Crypto |
| GitHub repository | Dive-into-Crypto |
| Android launcher, app UI, desktop UI | DVCRY |
| Full marketing name | DVCRY Deep Crypto Market Monitoring Tool |
| Google Play title | DVCRY: Crypto Market Monitor |
| Google Play short description | DVCRY Deep Crypto Market Monitoring Tool |

The requested full marketing name has 40 characters. Google Play allows 30 for the app title, 80 for the short description and 4,000 for the full description. The prepared English listing lives in [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US). These files do not upload themselves; paste them into Play Console or configure publishing automation later. Other stores have their own limits. [Google's listing limits](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en).

## 1. Prepare before paying

- The Android build targets `compileSdk = 36` / `targetSdk = 36` (AGP 8.11.1, Gradle 8.13), which meets the **API 36 or higher** requirement for new phone/tablet submissions. Test Android 16 behavior on a real device before submission. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).
- Configure a private release/upload keystore and back it up securely. Use the existing signing inputs documented in [packaging](packaging.md), build a signed release AAB, and verify its signature. A release build now refuses to run without a complete signing config (an unsigned release needs the explicit `-PallowUnsignedRelease=true` opt-in, meant for local smoke builds only), and the release workflow verifies both signatures (`jarsigner` for the AAB, `apksigner` for the APK). Never commit private keys or passwords.
- Retain `com.diveintocrypto.android` as the application ID; a display-name change does not require a new package identity. Increase `versionCode` for later uploaded updates. Configure Play App Signing when setting up the first release. [App bundles, signing and package identity](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en).
- Publish an accurate privacy policy at a public HTML URL and make it accessible inside the app. A bilingual draft lives at `docs/privacy-policy.html` (Markdown source: `docs/privacy-policy.md`); the in-app Privacy screen opens `PRIVACY_POLICY_URL` from `android/app/src/commonMain/kotlin/com/diveintocrypto/android/ui/settings/AppLinks.kt`, which stays empty until you set the hosted URL. Audit network requests, third-party libraries, local portfolio records, Android backup and deletion behavior before completing Data safety. Public market-data access alone does not establish that no user/device data is transmitted. [Privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- Prepare a 512 x 512 store icon, 1024 x 500 feature graphic and real Android screenshots. Check the current [preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en) before exporting. Confirm the draft description matches the tested Android build.
- Recruit people who can test scanning, unavailable data, notifications, settings, portfolio records and installation/update behavior on real devices.

## 2. Resolve the account type before paying

Google requires an **organization account** for financial products and services; an organization account normally requires a **D-U-N-S number**. D-U-N-S application itself is free. [Account types](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en).

DVCRY presents technical signals and a local portfolio view. Although it does not execute trades, hold funds or provide a wallet, that does not automatically exempt it from Google's broader financial-services rules. Whether this specific analysis-only app can use a personal account remains unresolved. Before registration/payment, ask Google Play developer support to classify the actual feature set, screenshots and listing. Do not select a personal account simply to avoid organization verification.

Suggested support inquiry (draft; not sent):

> DVCRY is a read-only cryptocurrency market monitoring and technical analysis app. It uses public Binance market data, displays technical signals, and offers a local portfolio view for manually entered positions. It does not execute trades, hold funds, provide wallet functionality, connect exchange accounts or provide personalized financial advice. Does this feature set require an organization developer account under your financial-services policy, or is a personal account permitted? Which Financial features declaration categories apply?

Complete the Financial features declaration according to the actual functionality. Do not declare the app an exchange/wallet when those features are absent, or select “no financial features” merely because it does not execute trades. [Declaration guidance](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en).

## 3. Register and create the listing

Publishing from your own Play Console developer account requires a **one-time US$25 registration fee**, even for an app offered free to users. There is no standard free-account route to a public Google Play listing. Complete identity/contact verification; personal-account owners must also verify access to a real Android device. [Registration requirements](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en).

After the correct account type is confirmed:

1. Register the developer account and complete verification.
2. Create DVCRY in Play Console, enter the prepared title and descriptions, upload graphics and provide a support email.
3. Complete the privacy policy, Data safety, Financial features declaration, content rating, target audience, ads and app-access questions accurately.
4. Upload a signed, API-compliant AAB to internal testing and inspect Play's validation and pre-launch results.

## 4. Test, apply for production and release

If Google permits a new personal account, run a **closed test with at least 12 testers opted in continuously for 14 days**, collect meaningful feedback and fix issues. Afterwards apply for production access and answer Google's testing/readiness questions. Fourteen days is a minimum testing period, not a promise of automatic approval or publication. Internal testing and off-Play APK sharing do not replace this requirement. Organization accounts are not subject to this specific new-personal-account gate. [Testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

Once production access and the release review are approved, roll out the production release and monitor crashes, provider failures and user feedback. Review time and any additional testing are controlled by Google; avoid promising a fixed launch date.

## If you want to spend nothing now

Prepare the code, listing, policy and tester group first. A signed release APK can also be distributed outside Google Play, for example through GitHub Releases or a website. Users must allow installation from that source; an AAB is not a directly installable APK. This does **not** create a Google Play listing. Check the applicable Android developer-verification rollout for the countries/devices you distribute to; off-Play distribution has its own evolving requirements. [Alternative distribution](https://developer.android.com/distribute/marketing-tools/alternative-distribution), [developer verification](https://developer.android.com/developer-verification/guides).

Recommended order: **account classification → API/signing/privacy preparation → developer registration → internal test → required closed test if applicable → production application → release**. The US$25 fee is the platform registration minimum; organization formation or other operating costs, if needed, are separate.
