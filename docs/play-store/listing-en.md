# Google Play store listing: English (en-US)

This file contains the English text to paste into Play Console. Limits: app name 30 characters, short description 80 characters, full description 4000 characters. Counts are in the "Character counts" section below. Fields marked `<OWNER: ...>` must be filled in before publishing.

The title and short description below are the same values as the canonical
`fastlane/metadata/android/en-US` files; see `docs/google-play-release.md` for the naming scheme
(project: Dive into Crypto, app: DVCRY).

## App name

```text
DVCRY: Crypto Market Monitor
```

## Short description

```text
DVCRY Deep Crypto Market Monitoring Tool
```

## Full description

```text
DVCRY analyses public market data from Binance USDT-M futures (price, volume, funding rate, open interest and more) with 60 technical indicators, and shows one analytics summary per symbol.

What it does
• Scans many USDT-M futures pairs in one list and ranks their analytics summaries.
• Shows indicator, timeframe and detail breakdowns for the symbol you choose.
• Lets you create alert rules for price, confidence score and open-interest thresholds; alerts arrive as local notifications on your device.
• Optional background scanning, with the top results of the latest scan in a home-screen widget.
• Keeps your portfolio entries (symbol, entry price, size, direction) on your device only.
• Shares result cards as PNG images.
• Turkish and English interface.

Important notice
• This app is not investment advice, portfolio management or a trading tool. It places no orders, has no access to your accounts, does not deposit or withdraw money and does not hold any crypto assets.
• The analytics shown are a mathematical summary of technical indicators, not a forecast of future prices. Past results do not indicate future results.
• Leveraged futures trading carries high risk and can cost you your entire deposit.
• Data comes from a third-party exchange's public APIs and may be delayed, incomplete or wrong.

Privacy
No account is required. The app requests only public market data and sends no personal data to the developer. Privacy policy: <OWNER: privacy policy URL>

DVCRY is independent and is not affiliated with, endorsed by or sponsored by Binance. The name Binance is used only to identify the data source.

Contact: <OWNER: support email>
```

## Character counts

Counting method: Python `len()`, one character per Unicode code point (spaces and line breaks included). The counted text is the content of the ```text blocks above.

| Field | Limit | Count | Status |
| --- | --- | --- | --- |
| App name | 30 | 28 | OK |
| Short description | 80 | 40 | OK |
| Full description | 4000 | 1657 | OK (placeholders are replaced by a URL and an email, a few characters more) |

## Notes (not published)

- Trademarks: "Binance" appears only as the nominative data-source name. No Binance logo, icon or mark is used in the icon, title, short description or screenshots. Check Binance's brand terms before publishing.
- "Deribit" is not mentioned because this release does not contact Deribit from any app screen.
- Words to avoid in any copy: "signal", "profit", "guaranteed", "accurate predictions", "most reliable", and hit-rate percentages.
- The "Important notice" section should match the in-app About screen and the privacy policy.
