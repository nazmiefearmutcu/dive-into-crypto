# Contributing to TRADING-BOT (TBV1)

Thanks for your interest. TBV1 is an opinionated personal-use trading bot;
external contributions are welcome but the bar is high because mistakes
have monetary consequences.

## Easiest contributions

- **Issue triage** — if a `Scanner` scan misbehaves on a specific symbol,
  open an issue with the symbol, timeframe, and the relevant logs from
  the `Logs` tab.
- **Indicator tuning** — the 15 indicator weights live in
  `macOS/config/default.yaml` (`indicator_weights`). PRs that adjust weights with a quantified
  out-of-sample backtest on named data are welcome.
- **Error-code reviews** — the `windows/` build ships 20 user-facing
  error codes (E001–E020). PRs that clarify wording or add new error
  codes are welcome.

## Code contributions

1. Fork the repo and branch from `main`.
2. macOS dev: `pip install -r macOS/requirements.txt` + run the dashboard
   with `python macOS/scripts/run_dashboard.py --host 127.0.0.1 --port 8081`.
3. Windows packaging: see `windows/README.md`. Verify the build still
   produces a working `.exe` and Desktop shortcut.
4. **Paper-mode safety net**: any PR that touches the execution path
   MUST keep paper mode as the unambiguous default. Live trading is
   per-credential explicit opt-in — there is no global "enable live"
   switch and there will not be one.

## What this project intentionally does NOT do

- Auto-deploy live trades on a user's behalf without a per-credential
  explicit-consent flow
- Connect to paid data feeds without a free-tier fallback
- Auto-update across major versions in live mode

PRs that change these defaults will be closed without merge.

## Code of conduct

Be respectful, be specific, be brief. Disagreements are fine; insults are not.
