"""Retry/backoff plumbing: transient upstream failures (429/5xx) are retried with
jittered exponential backoff honoring Retry-After; other errors propagate at once.
"""

import asyncio
from unittest.mock import patch

import pytest

from diveintocrypto_desktop.data import http
from diveintocrypto_desktop.data.http import TransientUpstreamError, retry_delay, run_with_retries


class _FakeResponse:
    def __init__(self, status: int, payload, retry_after: str | None = None) -> None:
        self.status = status
        self._payload = payload
        self.headers = {} if retry_after is None else {"Retry-After": retry_after}

    def raise_for_status(self) -> None:
        if self.status >= 400:
            import aiohttp

            raise aiohttp.ClientResponseError(
                request_info=None, history=(), status=self.status, message="err"
            )

    async def json(self):
        return self._payload


class _FakeSession:
    """Returns the queued responses in order; records request count."""

    def __init__(self, responses: list[_FakeResponse]) -> None:
        self.responses = list(responses)
        self.calls = 0

    def get(self, url, params=None):
        self.calls += 1
        resp = self.responses.pop(0)

        class _Ctx:
            async def __aenter__(self):
                return resp

            async def __aexit__(self, *exc):
                return None

        return _Ctx()


@pytest.mark.asyncio
async def test_get_json_retries_5xx_then_succeeds():
    session = _FakeSession([
        _FakeResponse(503, None),
        _FakeResponse(200, {"ok": 1}),
    ])
    sleeps: list[float] = []

    async def fake_sleep(seconds):
        sleeps.append(seconds)

    with patch.object(http, "get_session", return_value=session), \
         patch.object(http.asyncio, "sleep", fake_sleep):
        out = await http.get_json("https://fapi.example/fapi/v1/x")

    assert out == {"ok": 1}
    assert session.calls == 2
    assert len(sleeps) == 1 and sleeps[0] > 0


@pytest.mark.asyncio
async def test_get_json_honors_retry_after_header():
    session = _FakeSession([
        _FakeResponse(429, None, retry_after="3"),
        _FakeResponse(200, [1, 2]),
    ])
    sleeps: list[float] = []

    async def fake_sleep(seconds):
        sleeps.append(seconds)

    with patch.object(http, "get_session", return_value=session), \
         patch.object(http.asyncio, "sleep", fake_sleep):
        await http.get_json("https://fapi.example/fapi/v1/x")

    assert sleeps == [3.0]


@pytest.mark.asyncio
async def test_get_json_does_not_retry_client_errors():
    session = _FakeSession([_FakeResponse(400, None)])
    with patch.object(http, "get_session", return_value=session):
        with pytest.raises(Exception):
            await http.get_json("https://fapi.example/fapi/v1/x")
    assert session.calls == 1  # 4xx (bad symbol etc.) must not be retried


@pytest.mark.asyncio
async def test_get_json_raises_after_exhausting_retries():
    session = _FakeSession([_FakeResponse(500, None)] * 3)

    async def fake_sleep(seconds):
        pass

    with patch.object(http, "get_session", return_value=session), \
         patch.object(http.asyncio, "sleep", fake_sleep):
        with pytest.raises(TransientUpstreamError):
            await http.get_json("https://fapi.example/fapi/v1/x")
    assert session.calls == 3  # 1 attempt + 2 retries


def test_retry_delay_caps_and_jitter_bounds():
    assert retry_delay(10) <= 8.0  # jittered backoff is capped
    assert retry_delay(0, retry_after=120.0) == 60.0  # Retry-After honored, capped at 60
    assert retry_delay(2, retry_after=1.5) == 1.5


@pytest.mark.asyncio
async def test_run_with_retries_propagates_non_retryable_immediately():
    calls = 0

    async def send():
        nonlocal calls
        calls += 1
        raise ValueError("nope")

    async def fake_sleep(seconds):
        raise AssertionError("must not sleep for non-retryable errors")

    with pytest.raises(ValueError):
        await run_with_retries(send, sleep=fake_sleep)
    assert calls == 1
