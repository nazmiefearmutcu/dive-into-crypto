#!/usr/bin/env bash
# End-to-end smoke: launch the real service and verify it serves real data.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${1:-8799}"
BASE="http://127.0.0.1:${PORT}"

uv run dive-desktop --no-open --port "$PORT" >/tmp/dive-desktop-e2e.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT

# wait for health (up to ~30s)
for i in $(seq 1 60); do
  if curl -fs "$BASE/api/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

echo "== /api/health ==";  curl -fs "$BASE/api/health"; echo
echo "== /api/universe?limit=5 =="; curl -fs "$BASE/api/universe?limit=5" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("perps:",[r["s"] for r in d])'
echo "== /api/symbol/BTCUSDT =="; curl -fs "$BASE/api/symbol/BTCUSDT" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("BTCUSDT:",d["finalSignal"],d["confidence"],"% | multiTf",len(d["multiTf"]),"| indicators",len(d["indicators"]),"| price",d["price"])'
echo "== /api/scan?size=5&universe_limit=10 =="; curl -fs "$BASE/api/scan?size=5&universe_limit=10" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("universe",d["universeCount"],"survivors",[(r["s"],r["finalSignal"],round(r["netNss"])) for r in d["survivors"]])'
echo "== /api/logs (most recent) =="; curl -fs "$BASE/api/logs" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0] if d else "empty")'
echo "E2E OK"
