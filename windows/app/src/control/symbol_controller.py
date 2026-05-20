"""Symbol controller - monitors active_symbol.txt for symbol changes."""

from pathlib import Path
from typing import Optional

from src.utils.logger import get_logger
from src.utils.validators import validate_symbol

logger = get_logger("control.symbol_controller")


class SymbolController:
    """Monitors active_symbol.txt and detects symbol changes."""

    def __init__(self, symbol_file_path: str) -> None:
        self.symbol_file = Path(symbol_file_path)
        self.current_symbol: Optional[str] = None
        self._ensure_file_exists()

    def _ensure_file_exists(self) -> None:
        """Create the symbol file with default if it doesn't exist."""
        if not self.symbol_file.exists():
            self.symbol_file.parent.mkdir(parents=True, exist_ok=True)
            self.symbol_file.write_text("BTCUSDT\n")
            logger.info(f"Created default symbol file: {self.symbol_file}")

    def read_symbol(self) -> Optional[str]:
        """Read the current symbol from file."""
        try:
            content = self.symbol_file.read_text().strip().upper()
            if not content:
                return None
            # Take the first line only
            symbol = content.split("\n")[0].strip()
            if validate_symbol(symbol):
                return symbol
            else:
                logger.warning(f"Invalid symbol in file: '{symbol}'")
                return None
        except Exception as e:
            logger.error(f"Error reading symbol file: {e}")
            return None

    def check_for_change(self) -> tuple[bool, Optional[str]]:
        """Check if the symbol has changed.

        Returns:
            (changed: bool, new_symbol: Optional[str])
        """
        new_symbol = self.read_symbol()
        if new_symbol is None:
            return False, self.current_symbol

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
            self.symbol_file.write_text(f"{symbol}\n")
            self.current_symbol = symbol
            logger.info(f"Symbol set to: {symbol}")
            return True
        except Exception as e:
            logger.error(f"Error writing symbol file: {e}")
            return False
