"""FastAPI service tests. Offline: health/logs. @live: real symbol/scan/universe."""

import pytest
from fastapi.testclient import TestClient

from diveintocrypto_desktop.api.app import create_app

client = TestClient(create_app())


def test_health_ok():
    r = client.get("/api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["service"] == "dive-into-crypto-desktop"


def test_logs_endpoint_returns_list():
    r = client.get("/api/logs")
    assert r.status_code == 200
    assert isinstance(r.json(), list)


def test_routes_registered():
    paths = {r.path for r in create_app().routes}
    assert {"/api/health", "/api/universe", "/api/scan", "/api/symbol/{symbol}", "/api/leaders", "/api/logs"} <= paths


# NOTE: live API behaviour is verified by a real subprocess end-to-end (T9), not
# via TestClient — TestClient runs each request on a fresh event loop, which the
# process-wide aiohttp session (correct under uvicorn's single loop) does not
# support. See tests/e2e_smoke.sh.
