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
