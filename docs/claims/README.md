# Claims Registry

Pre-registered, falsifiable statements about the engine's graded performance.

- One YAML file per claim, named `<claim_id>.yaml`. **Registry files are
  immutable by convention** — a claim id can never be overwritten or edited in
  place (the API enforces this with `409 claim_exists`).
- A claim is evaluated ONLY on grades whose verdict `ts` is AFTER
  `registered_at` — no peeking at the past the claim wasn't written for.
- Status is one of `PENDING` (fewer than `min_n` post-registration grades),
  `CONFIRMED` (metric meets the threshold) or `REFUTED` (it doesn't).
- The last evaluation is mirrored to a sidecar status file
  (`desktop/backend/runtime/claims_status.json`, env
  `DIVE_CLAIMS_STATUS_PATH`); the registry itself is never mutated.

Schema (`POST /api/claims` validates exactly this):

| Field | Type | Meaning |
|---|---|---|
| `claim_id` | string | `[A-Za-z0-9_-]+`, unique |
| `registered_at` | ISO-8601 Z | evaluation cutoff (strictly-after) |
| `engine_version` | string | archive writer the claim was registered under |
| `claim` | string | the falsifiable statement |
| `metric` | string | `hit_rate` or `avg_forward` |
| `filter` | object | subset of `verdict`, `regime`, `divergence_tier`, `session`, `funding_proximity` |
| `horizon` | string | `1h` / `4h` / `24h` |
| `min_n` | int | minimum post-registration grades before a verdict |
| `threshold` | object | `{"op": ">=" | "<=", "value": number}` |
