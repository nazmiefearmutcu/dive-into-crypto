"""Tests for the symbol controller."""

import pytest
from pathlib import Path
import tempfile

from src.control.symbol_controller import SymbolController


@pytest.fixture
def symbol_file(tmp_path):
    f = tmp_path / "active_symbol.txt"
    f.write_text("BTCUSDT\n")
    return f


@pytest.fixture
def controller(symbol_file):
    return SymbolController(str(symbol_file))


class TestSymbolController:
    def test_read_symbol(self, controller):
        symbol = controller.read_symbol()
        assert symbol == "BTCUSDT"

    def test_initial_load(self, controller):
        changed, symbol = controller.check_for_change()
        assert changed is True  # First load is always a "change"
        assert symbol == "BTCUSDT"

    def test_no_change(self, controller):
        controller.check_for_change()  # First load
        changed, symbol = controller.check_for_change()
        assert changed is False
        assert symbol == "BTCUSDT"

    def test_detect_symbol_change(self, controller, symbol_file):
        controller.check_for_change()  # First load

        # Simulate user changing the file
        symbol_file.write_text("ETHUSDT\n")
        changed, symbol = controller.check_for_change()
        assert changed is True
        assert symbol == "ETHUSDT"

    def test_set_symbol(self, controller, symbol_file):
        assert controller.set_symbol("SOLUSDT")
        assert controller.current_symbol == "SOLUSDT"
        assert symbol_file.read_text().strip() == "SOLUSDT"

    def test_invalid_symbol_rejected(self, controller):
        assert not controller.set_symbol("inv@lid!")

    def test_get_current_symbol(self, controller):
        symbol = controller.get_current_symbol()
        assert symbol == "BTCUSDT"

    def test_empty_file_returns_none(self, tmp_path):
        f = tmp_path / "empty.txt"
        f.write_text("")
        sc = SymbolController(str(f))
        assert sc.read_symbol() is None

    def test_creates_default_file_if_missing(self, tmp_path):
        f = tmp_path / "nonexistent" / "symbol.txt"
        sc = SymbolController(str(f))
        assert f.exists()
        assert f.read_text().strip() == "BTCUSDT"

    def test_lowercase_symbol_converted(self, tmp_path):
        f = tmp_path / "sym.txt"
        f.write_text("ethusdt\n")
        sc = SymbolController(str(f))
        assert sc.read_symbol() == "ETHUSDT"

    def test_multiple_lines_takes_first(self, tmp_path):
        f = tmp_path / "multi.txt"
        f.write_text("BTCUSDT\nETHUSDT\nSOLUSDT\n")
        sc = SymbolController(str(f))
        assert sc.read_symbol() == "BTCUSDT"
