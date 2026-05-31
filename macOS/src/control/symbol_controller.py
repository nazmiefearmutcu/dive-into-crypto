"""Symbol controller - monitors active_symbol.txt for symbol changes."""

import os
import tempfile
from pathlib import Path
from typing import Optional

from src.utils.logger import get_logger
from src.utils.validators import validate_symbol

logger = get_logger("control.symbol_controller")
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_SYMBOL_FILE = PROJECT_ROOT / "runtime" / "active_symbol.txt"


def _resolve_symbol_file(symbol_file_path: str | Path) -> Path:
    text = str(symbol_file_path).strip()
    if text in {"", "."}:
        return DEFAULT_SYMBOL_FILE

    path = Path(text).expanduser()
    if not path.is_absolute():
        path = PROJECT_ROOT / path

    if path.exists() and path.is_dir():
        return path / "active_symbol.txt"
    if not path.suffix and not path.exists():
        return path / "active_symbol.txt"
    return path


def _atomic_write_text(path: Path, content: str) -> None:
    """Write text atomically via temp file + rename."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError as cleanup_exc:
            logger.warning(f"Failed to remove temp file {tmp_name}: {cleanup_exc}")
        raise


class SymbolController:
    """Monitors active_symbol.txt and detects symbol changes."""

    def __init__(self, symbol_file_path: str) -> None:
        if isinstance(symbol_file_path, (str, Path)):
            self.symbol_file = _resolve_symbol_file(symbol_file_path)
        else:
            self.symbol_file = DEFAULT_SYMBOL_FILE
        self.current_symbol: Optional[str] = None
        self._ensure_file_exists()

    def _ensure_file_exists(self) -> None:
        """Create the symbol file with default if it doesn't exist."""
        if not self.symbol_file.exists():
            _atomic_write_text(self.symbol_file, "BTCUSDT\n")
            logger.info(f"Created default symbol file: {self.symbol_file}")

    def read_symbol(self) -> Optional[str]:
        """Read the current symbol from file."""
        try:
            content = self.symbol_file.read_text(encoding="utf-8").strip().upper()
            if not content:
                return None
            # Take the first line only
            symbol = content.split("\n")[0].strip()
            if validate_symbol(symbol):
                return symbol
            else:
                logger.warning(f"Invalid symbol in file: '{symbol}'")
                try:
                    self.symbol_file.unlink(missing_ok=True)
                except Exception as cleanup_exc:
                    logger.warning(
                        f"Failed to remove invalid symbol file {self.symbol_file}: {cleanup_exc}"
                    )
                return None
        except Exception as e:
            logger.error(f"Error reading symbol file: {e}")
            try:
                if self.symbol_file.exists():
                    self.symbol_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove unreadable symbol file {self.symbol_file}: {cleanup_exc}"
                )
            return None

    def check_for_change(self) -> tuple[bool, Optional[str]]:
        """Check if the symbol has changed.

        Returns:
            (changed: bool, new_symbol: Optional[str])
        """
        new_symbol = self.read_symbol()
        if new_symbol is None:
            if self.current_symbol is not None:
                logger.warning(
                    f"Active symbol file unavailable or invalid; clearing stale symbol {self.current_symbol}"
                )
            self.current_symbol = None
            return False, None

        if new_symbol != self.current_symbol:
            old_symbol = self.current_symbol
            self.current_symbol = new_symbol
            if old_symbol is not None:
                logger.info(f"Symbol changed: {old_symbol} -> {new_symbol}")
            else:
                logger.info(f"Initial symbol loaded: {new_symbol}")
            return True, new_symbol

        return False, self.current_symbol

    def get_current_symbol(self) -> Optional[str]:
        """Get the currently active symbol."""
        if self.current_symbol is None:
            _, symbol = self.check_for_change()
            return symbol
        return self.current_symbol

    def set_symbol(self, symbol: str) -> bool:
        """Programmatically set the active symbol."""
        symbol = symbol.strip().upper()
        if not validate_symbol(symbol):
            logger.error(f"Cannot set invalid symbol: {symbol}")
            return False
        try:
            _atomic_write_text(self.symbol_file, f"{symbol}\n")
            self.current_symbol = symbol
            logger.info(f"Symbol set to: {symbol}")
            return True
        except Exception as e:
            logger.error(f"Error writing symbol file: {e}")
            try:
                if self.symbol_file.exists():
                    self.symbol_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale symbol file after write error: {cleanup_exc}"
                )
            return False
