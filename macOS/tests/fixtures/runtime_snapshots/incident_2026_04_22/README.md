# Runtime snapshot — incident 2026-04-22

Frozen copy of `macOS/runtime/` taken on 2026-05-21 during Recovery Session 1.

## Why this exists

Before this rescue, `macOS/runtime/*.json` and the `auto_*_disabled` flag files
were tracked in git via an explicit whitelist in `.gitignore`. That meant every
live-bot tick that rewrote those files showed up as a working-tree diff, and
made the on-disk runtime state effectively the source of truth.

The files captured here are the exact snapshot that was in the index at the
time of the cleanup, including the known bad state:

- `dashboard_status.json` — `current_price: null`, `cycle_count: 0`,
  `last_update: 2026-04-22`, `open_positions_count: 9`. Stale write that was
  never reconciled.
- `state.json` — `paper_balance: -1212118.0633`. Paper-mode wallet went
  negative; the live engine should never have been allowed to push the balance
  past zero. Treat as a regression-test anchor, not as a target to reproduce.
- `auto_scan_disabled`, `auto_select_disabled` — sentinel flags that were
  committed by accident. They are runtime state, not config.

## Provenance

| File | SHA-256 |
|------|---------|
| `active_coin_signals.json` | `dd9cf3f944d98b60ab047fdfc633e633905fbd9d1fecc16e11c604e400985a4a` |
| `active_symbol.txt` | `cf1ecde007d9de6879ad4eba627c2a1a53b2a98dc3c6ec4abde9af787908ddbc` |
| `auto_scan_disabled` | `b5ea375becd3088862c16fc97fe379532c583079829fcf1fdcb549e6808262fb` |
| `auto_scan_progress.json` | `8c378c6f156393c1086aefa40fcf57abbd163a354adab8a5fc0c1bc82f28a835` |
| `auto_select_disabled` | `b5ea375becd3088862c16fc97fe379532c583079829fcf1fdcb549e6808262fb` |
| `dashboard_status.json` | `5637b4cf7a111f1533395971e8f82648d695c5d3fa9b84f5a9e64a7295cff5ee` |
| `manual_scan_active.json` | `1afa67476ff526371ee9d19fd97e262599cd1898f0491973bd73577c07f00c78` |
| `multi_scan_results.json` | `f09a2289d4e967d469cc5507cb8b7770dbc88d0ff587b740b406ddb28311a149` |
| `state.json` | `c6f797926f8e61ca18583c2c849112910f75dbe83a182f863c79e2eb1f7ee1af` |

## Do not edit

Treat this directory as read-only history. If a regression test needs to assert
on the incident, load files from here directly — do not promote them into
`macOS/runtime/`, do not normalize, do not clean.

If you need a fresh snapshot of a future incident, create a new sibling
directory (`incident_YYYY_MM_DD/`) — do not overwrite this one.
