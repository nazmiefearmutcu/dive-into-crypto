"""Helpers shared by the E2E suites.

* Source oracle: read android/app/build.gradle.kts (the suite cannot execute Gradle), so
  release-pipeline checks run against the real file instead of a copy of it.
* Websocket frame reads for ``/api/live``.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GRADLE_KTS = REPO_ROOT / "android" / "app" / "build.gradle.kts"
_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT = re.compile(r"(?<![:\"])//[^\n]*")


def gradle_code() -> str:
    """build.gradle.kts with comments stripped, so a check cannot pass on a comment."""
    text = GRADLE_KTS.read_text(encoding="utf-8")
    return _LINE_COMMENT.sub("", _BLOCK_COMMENT.sub("", text))


# The former KEYWORDS_MAP / SWAP_REGEX oracle is gone: the Android consensus engine no
# longer mirrors narrative text by keyword swapping; it renders each narrative cell
# explicitly through ConsensusLabels (TR/EN via DiveStrings), covered by the Kotlin
# unit tests (ConsensusEngineTest, DiveFormattersTest).


async def frame_for(ws, symbol: str, *, attempts: int = 4, timeout: float = 15.0) -> dict:
    """Read frames until one is for ``symbol``.

    /api/live answers the default BTCUSDT first when a symbol sent right after connect
    arrives after the server's 100 ms read window; the frame for the requested symbol
    then follows on the server's next tick.
    """
    seen = []
    for _ in range(attempts):
        data = json.loads(await ws.recv(timeout=timeout))
        key = data.get("s", data.get("symbol"))
        if key == symbol:
            return data
        seen.append(key)
    raise AssertionError(f"no frame for {symbol} within {attempts} frames; saw {seen}")
