"""Log reader - reads and filters bot.log for the dashboard."""

import os
from collections import deque
from pathlib import Path
from typing import Optional

from src.utils.logger import get_logger

logger = get_logger("monitoring.log_reader")

DEFAULT_LOG_PATH = "runtime/bot.log"


class LogReader:
    """Reads the bot log file tail and supports level filtering."""

    def __init__(self, log_path: str = DEFAULT_LOG_PATH) -> None:
        self.log_path = Path(log_path)

    def read_tail(self, n: int = 200) -> list[str]:
        """Read the last N lines from the log file."""
        if not self.log_path.exists():
            return []
        try:
            with open(self.log_path, "r", encoding="utf-8", errors="replace") as f:
                return list(deque(f, maxlen=n))
        except Exception as e:
            logger.error(f"Error reading log file: {e}")
            return []

    def read_filtered(
        self,
        n: int = 200,
        level: Optional[str] = None,
        search: Optional[str] = None,
    ) -> list[dict[str, str]]:
        """Read and filter log lines into structured entries.

        Args:
            n: Max lines to read from tail.
            level: Filter by level (INFO, WARNING, ERROR, DEBUG).
            search: Filter by substring search.

        Returns:
            List of dicts with keys: timestamp, level, module, message, raw.
        """
        raw_lines = self.read_tail(n * 2 if level or search else n)
        entries: list[dict[str, str]] = []

        for line in raw_lines:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            entry = self._parse_line(line)
            if level and entry["level"].upper() != level.upper():
                continue
            if search and search.lower() not in line.lower():
                continue
            entries.append(entry)

        return entries[-n:]

    def _parse_line(self, line: str) -> dict[str, str]:
        """Parse a structured log line into components.

        Expected format: 2024-01-15 14:00:01 | INFO     | module_name | message
        """
        parts = line.split(" | ", 3)
        if len(parts) >= 4:
            return {
                "timestamp": parts[0].strip(),
                "level": parts[1].strip(),
                "module": parts[2].strip(),
                "message": parts[3].strip(),
                "raw": line,
            }
        elif len(parts) >= 2:
            return {
                "timestamp": parts[0].strip(),
                "level": parts[1].strip() if len(parts) > 1 else "INFO",
                "module": "",
                "message": parts[-1].strip(),
                "raw": line,
            }
        return {
            "timestamp": "",
            "level": "INFO",
            "module": "",
            "message": line,
            "raw": line,
        }

    def get_file_size(self) -> int:
        """Get the log file size in bytes."""
        if self.log_path.exists():
            return self.log_path.stat().st_size
        return 0

    def get_line_count(self) -> int:
        """Approximate line count."""
        if not self.log_path.exists():
            return 0
        try:
            with open(self.log_path, "r", encoding="utf-8", errors="replace") as f:
                return sum(1 for _ in f)
        except Exception:
            return 0
