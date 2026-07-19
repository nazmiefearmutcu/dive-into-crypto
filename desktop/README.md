# Dive Into Crypto — Desktop

A single-window terminal for the Binance USDT-M perpetual-futures market. The **reference**
consensus engine — 57 indicators across 12 timeframes, three futures-native overlays, and a
whale-divergence filter — fed with highest-fidelity data through
[**Crypcodile**](https://github.com/nazmiefearmutcu/Crypcodile), rendered in the **Depth Terminal**
UI.

| Scanner | Panel |
| --- | --- |
| ![Scanner](../docs/screenshots/desktop-scanner.png) | ![Panel](../docs/screenshots/desktop-panel.png) |

> Analysis & research tool — **not financial advice**, places **no orders**. Public data only.

---

## Quickstart

Requires **Python 3.12+** and [`uv`](https://docs.astral.sh/uv/). The UI ships prebuilt; you do
**not** need Node to run it.

```bash
cd desktop/backend
uv sync                  # backend + Crypcodile (pinned commit)
uv run dive-desktop      # serves 127.0.0.1:8780 and opens the UI

uv run dive-desktop --no-open        # serve only
uv run dive-desktop --port 8888      # custom port
```

Everything runs locally; the only network traffic is **public** Binance market-data requests.

---

## Architecture

```
Binance public REST/WS ─┐
Deribit (IV / DVOL)     ─┤
                        ▼
                  ┌──────────────┐
                  │  Crypcodile  │  OHLCV · funding · OI · mark · IV   (data engine)
                  └──────┬───────┘
 Binance futures/data ───┤  global / top-trader / taker long-short ratios
                         ▼
            ┌───────────────────────────────────────────────┐
            │  backend/   Python · FastAPI                   │
            │   engine/   57 indicators + consensus + risk   │
            │   scan/     whale divergence · microstructure  │
            │             · regime · MTF-confluence overlays │
            │             · two-phase ranking + backfill     │
            │   data/     Crypcodile-fed klines/OI/ratios…   │
            │   api/      REST + WS + serves the built UI    │
            └───────────────┬───────────────────────────────┘
                            │  JSON (per-symbol data-contract) · WS live
                            ▼
            ┌───────────────────────────────────────────────┐
            │  ui/   React · "Depth Terminal"                │
            │   desktop-app.jsx  status strip · icon rail ·  │
            │                    Scanner · Panel · overlays  │
            │   data.js          → live backend adapter      │
            │   mock.js          → offline demo fallback     │
            └───────────────────────────────────────────────┘
```

**Engine.** The backend *is* the canonical Python reference. Its 15-indicator core is pinned to the
Android `BTCUSDT 1h × 300` fixtures (signal + score, exact) — cross-language parity is enforced
per-indicator. The desktop engine extends that core to 57 indicators plus three overlays
(futures-microstructure, regime-adaptive weighting, MTF-confluence) that annotate but never alter
the parity-locked vote.

**Honest data.** Every number in the live app is real, derived from Binance via Crypcodile. There is
no synthesised path in production; when a source is unavailable it is shown as unavailable (e.g. a
`502` on a symbol). `mock.js` exists only as an offline demo fallback for the UI and is never used
while the backend is reachable.

---

## Depth Terminal

A precision instrument, not a dashboard: blueprint grid, schematic corner-brackets, hairline rulers,
tabular numerics, a single phosphor accent, glow only where alive. Fonts: Martian Mono / IBM Plex
Mono / Newsreader. Four themes, switched live from the status strip (persisted locally):

| Theme | Look |
| --- | --- |
| **Phosphor** | green phosphor on near-black · scanlines |
| **Amber** | amber monochrome CRT |
| **Ice** | cool cyan minimal |
| **Paper** | light, ink-on-paper |

### Screens
**Tarama** (ranked sweep · whale-divergence elimination) · **Panel** (active symbol: 12-TF heat,
serif verdict, family-grouped 57-indicator table, and instrument gauges for the
microstructure / regime / MTF overlays + whale divergence) · **OI · L/S** · **Sinyal** · **Ağ
Günlüğü** (live request log) · **Ayarlar**. Deep-link a view with `#panel`, `#scan`, …

---

## Development

```bash
# backend tests (offline parity + parsers)
cd desktop/backend && uv run pytest -q
uv run pytest -m live -q                # network-gated, against live Binance

# rebuild the UI bundle (needs Node)
cd desktop/ui && node build.mjs         # → ui/dist/{bundle.js, styles.css, index.html}
```

The UI is bundled with esbuild into a single offline `bundle.js` (no CDN). Source of truth:
`ui/src/app/desktop-app.jsx` + `ui/src/styles.css`; the standalone design reference is
`ui/design/dive-terminal.html`.

---

## Troubleshooting

- **"Backend'e bağlanılamadı"** — the service isn't running; `uv run dive-desktop`.
- **Empty scan / symbol errors** — Binance futures data is geo-restricted in some regions; a network
  condition, not a bug. Try a region where Binance Futures is reachable.
- **Rate limits** — the scan caches briefly and bounds the expensive futures-data calls; rapid manual
  refreshes may be throttled by Binance.

## License

[MIT](../LICENSE) © nazmiefearmutcu
