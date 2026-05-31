"""Scanner service - scans Binance futures for high-confidence trading opportunities."""

import os
import tempfile
import time
import threading
from pathlib import Path
from typing import Any, Callable, Optional

from src.api.binance_client import BinanceClient
from src.data.market_data import MarketDataProvider
from src.services.signal_service import SignalService
from src.consensus.engine import ConsensusEngine
from src.utils.validators import validate_timeframe
from src.utils.logger import get_logger

logger = get_logger("services.scanner")


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_file:
            tmp_file.write(content)
            tmp_file.flush()
            os.fsync(tmp_file.fileno())
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError as cleanup_exc:
            logger.warning(f"Failed to remove temp file {tmp_name}: {cleanup_exc}")
        raise

# Stablecoins and low-quality pairs to skip
SKIP_SYMBOLS = {
    "USDCUSDT", "BUSDUSDT", "TUSDUSDT", "DAIUSDT", "FDUSDUSDT",
    "EURUSDT", "GBPUSDT",
}

# No limit — scan all available futures symbols


class ScannerService:
    """Scans multiple coins and ranks them by confidence."""

    def __init__(self, config: dict[str, Any], symbol_file: Path | None = None,
                 timeframe: str | None = None,
                 shared_client: BinanceClient | None = None,
                 shared_symbols: list[str] | None = None,
                 sleeper: Callable[[float], None] | None = None) -> None:
        self.config = config
        cfg = dict(config)
        market_type = cfg.get("market_type", "spot")
        self.market_type = market_type.strip().lower() if isinstance(market_type, str) else "spot"
        if self.market_type not in {"spot", "futures"}:
            raise ValueError(f"Invalid market_type: {self.market_type}")
        # Reuse shared client to avoid rate limits on multi-TF scans
        if shared_client:
            self.client = shared_client
        else:
            self.client = BinanceClient(config)
            self.client.initialize()
        # Allow timeframe override (for multi-TF scanning)
        if timeframe:
            tf = timeframe.strip() if isinstance(timeframe, str) else timeframe
            if isinstance(tf, str):
                cfg["timeframe"] = "1M" if tf.upper() == "1M" else tf.lower()
            else:
                cfg["timeframe"] = timeframe
        tf_value = cfg.get("timeframe", "1h")
        if not isinstance(tf_value, str):
            raise ValueError(f"Invalid timeframe type: {type(tf_value).__name__}")
        tf_value = tf_value.strip()
        cfg["timeframe"] = "1M" if tf_value.upper() == "1M" else tf_value.lower()
        if not validate_timeframe(cfg["timeframe"]):
            raise ValueError(f"Invalid timeframe: {tf_value}")
        self.market_data = MarketDataProvider(self.client, cfg)
        self.signal_service = SignalService(cfg)
        self.consensus_engine = ConsensusEngine(cfg)
        self.symbol_file = symbol_file
        self.timeframe = cfg["timeframe"]
        self._shared_symbols = shared_symbols
        self._request_delay = 0.1  # default; increased for multi-TF mode
        # S6: injectable rate-limit seam so scan loops can be tested
        # without sleeping or threading races. Defaults to `time.sleep`
        # so production behavior is unchanged.
        self._sleeper: Callable[[float], None] = sleeper or time.sleep

        # Scan state
        self._scanning = False
        self._scan_results: list[dict] = []
        self._scan_progress: dict = {
            "current": 0, "total": 0, "symbol": "",
            "status": "idle", "hot_count": 0,
            "warnings": [],
        }
        self._lock = threading.Lock()
        self._scan_generation = 0
        self._last_analyze_error: str | None = None
        self._last_indicator_errors: list[str] = []

    @property
    def is_scanning(self) -> bool:
        return self._scanning

    @property
    def progress(self) -> dict:
        with self._lock:
            return dict(self._scan_progress)

    @property
    def results(self) -> list[dict]:
        with self._lock:
            return list(self._scan_results)

    @property
    def last_indicator_errors(self) -> list[str]:
        with self._lock:
            return list(self._last_indicator_errors)

    def _get_top_symbols_by_volume(self) -> list[str]:
        """Get symbols sorted by 24h volume (descending)."""
        try:
            if self.market_type == "futures":
                tickers = self.client.client.futures_ticker()
            else:
                tickers = self.client.client.get_ticker()
            usdt_tickers = [
                t for t in tickers
                if str(t.get("symbol", "")).endswith("USDT")
                and t.get("symbol") not in SKIP_SYMBOLS
            ]
            def _quote_volume(v: dict[str, Any]) -> float:
                try:
                    return float(v.get("quoteVolume", 0) or 0)
                except Exception:
                    return 0.0

            usdt_tickers.sort(key=_quote_volume, reverse=True)
            symbols = [t["symbol"] for t in usdt_tickers]
            logger.info(f"Top {len(symbols)} symbols by volume selected for scan")
            return symbols
        except Exception as e:
            logger.error(f"Failed to get tickers, falling back to symbol list: {e}")
            if self.market_type == "futures":
                symbols = self.client.get_futures_symbols()
            else:
                symbols = []
                try:
                    symbols = [
                        str(t.get("symbol", ""))
                        for t in self.client.client.get_ticker()
                        if str(t.get("symbol", "")).endswith("USDT")
                    ]
                except Exception as exc:
                    logger.warning(f"Fallback ticker fetch failed during scan setup: {exc}")
            return [s for s in symbols if s not in SKIP_SYMBOLS]

    def set_active_symbol(self, symbol: str) -> bool:
        """Write symbol to active_symbol.txt so the bot switches to it."""
        if self.symbol_file is None:
            return False
        try:
            self.symbol_file.parent.mkdir(parents=True, exist_ok=True)
            _atomic_write_text(self.symbol_file, f"{symbol}\n")
            logger.info(f"Active symbol set to {symbol}")
            return True
        except Exception as e:
            logger.error(f"Failed to set active symbol: {e}")
            try:
                if self.symbol_file.exists():
                    self.symbol_file.unlink()
            except Exception as cleanup_exc:
                logger.warning(
                    f"Failed to remove stale active symbol file after write error: {cleanup_exc}"
                )
            return False

    def scan(self, min_confidence: int = 55, generation: int | None = None) -> list[dict]:
        """Scan all top symbols and return those with confidence >= threshold.
        Scans ALL coins and builds a ranked list (does NOT stop at first match)."""
        try:
            symbols = self._shared_symbols if self._shared_symbols is not None else self._get_top_symbols_by_volume()
            with self._lock:
                self._last_indicator_errors = []

            if not symbols:
                logger.error("No futures symbols found")
                with self._lock:
                    if generation == self._scan_generation:
                        self._scanning = False
                        self._scan_progress = {
                            "current": 0,
                            "total": 0,
                            "symbol": "",
                            "status": "error",
                            "hot_count": 0,
                            "warnings": [],
                        }
                return []

            with self._lock:
                if generation is None:
                    self._scan_generation += 1
                    generation = self._scan_generation
                elif generation != self._scan_generation:
                    logger.info("Scan cancelled before start")
                    return []
                self._scanning = True
                self._scan_results = []
                self._last_indicator_errors = []
                self._scan_progress = {
                    "current": 0,
                    "total": len(symbols),
                    "symbol": "",
                    "status": "scanning",
                    "hot_count": 0,
                    "warnings": [],
                }

            logger.info(f"Scanning {len(symbols)} futures symbols (min confidence: {min_confidence}%)")

            hot_count = 0
            analysis_failure_count = 0
            for i, symbol in enumerate(symbols):
                with self._lock:
                    if generation != self._scan_generation or not self._scanning:
                        logger.info("Scan cancelled")
                        break
                    self._scan_progress["current"] = i + 1
                    self._scan_progress["symbol"] = symbol

                result = self._analyze_symbol(symbol)
                if result is None:
                    if self._last_analyze_error is not None:
                        analysis_failure_count += 1
                    continue

                with self._lock:
                    if generation != self._scan_generation or not self._scanning:
                        logger.info("Scan cancelled after analysis")
                        break
                    self._scan_results.append(result)
                    if result["confidence"] >= min_confidence and result["signal"] != "NEUTRAL":
                        hot_count += 1
                        self._scan_progress["hot_count"] = hot_count

                self._sleeper(self._request_delay)

            # Sort results by confidence descending
            with self._lock:
                if generation != self._scan_generation:
                    self._scanning = False
                    self._scan_progress["status"] = "idle"
                    return []
                if symbols and analysis_failure_count == len(symbols):
                    logger.error("Scan failed: all symbol analyses failed")
                    self._scanning = False
                    self._scan_progress["status"] = "error"
                    return []
                self._scan_results.sort(key=lambda r: r["confidence"], reverse=True)
                self._scanning = False
                self._scan_progress["status"] = "complete"
                self._scan_progress["hot_count"] = hot_count

            logger.info(f"Scan complete. {hot_count} coins above {min_confidence}% confidence out of {len(self._scan_results)} analyzed.")
            return [r for r in self._scan_results if r["confidence"] >= min_confidence and r["signal"] != "NEUTRAL"]

        except Exception as e:
            logger.error(f"Scan crashed: {e}", exc_info=True)
            with self._lock:
                if generation == self._scan_generation:
                    self._scanning = False
                    self._scan_progress["status"] = "error"
            return []

    def scan_async(self, min_confidence: int = 55) -> bool:
        """Start scanning in a background thread."""
        with self._lock:
            if self._scanning:
                return False
            self._scan_generation += 1
            generation = self._scan_generation
            self._scanning = True
            self._scan_results = []
            self._scan_progress = {
                "current": 0,
                "total": 0,
                "symbol": "",
                "status": "scanning",
                "hot_count": 0,
                "warnings": [],
            }
        try:
            thread = threading.Thread(target=self.scan, args=(min_confidence, generation), daemon=True)
            thread.start()
            return True
        except Exception as exc:
            logger.error(f"Failed to start scan thread: {exc}", exc_info=True)
            with self._lock:
                if generation == self._scan_generation:
                    self._scanning = False
                    self._scan_progress = {
                        "current": 0,
                        "total": 0,
                        "symbol": "",
                        "warnings": [],
                        "status": "error",
                        "hot_count": 0,
                    }
            return False

    def stop(self) -> None:
        """Stop an ongoing scan."""
        with self._lock:
            self._scan_generation += 1
            self._scanning = False

    def force_reset(self) -> None:
        """Force reset scanning state (recover from stuck state)."""
        with self._lock:
            self._scan_generation += 1
            self._scanning = False
            self._scan_results = []
            self._last_indicator_errors = []
            self._scan_progress = {
                "current": 0,
                "total": 0,
                "symbol": "",
                "status": "idle",
                "hot_count": 0,
                "warnings": [],
            }

    def _analyze_symbol(self, symbol: str) -> Optional[dict]:
        """Analyze a single symbol and return its consensus data."""
        self._last_analyze_error = None
        try:
            df = self.market_data.get_ohlcv(symbol)
            if df is None or df.empty:
                self._last_analyze_error = "empty market data"
                logger.warning(f"No market data for {symbol}")
                return None

            current_price = float(df["close"].iloc[-1])
            indicator_results = self.signal_service.calculate_all(df)
            indicator_errors = list(getattr(self.signal_service, "last_errors", []))
            if indicator_errors:
                with self._lock:
                    prefixed_errors = [f"{symbol}: {err}" for err in indicator_errors]
                    self._last_indicator_errors.extend(prefixed_errors)
                    warnings = self._scan_progress.get("warnings")
                    if not isinstance(warnings, list):
                        warnings = []
                    warnings.extend(prefixed_errors)
                    self._scan_progress["warnings"] = warnings
            consensus = self.consensus_engine.evaluate(indicator_results)

            result = {
                "symbol": symbol,
                "price": current_price,
                "signal": consensus.get("final_signal", "NEUTRAL"),
                "confidence": consensus.get("confidence", 0),
                "risk_level": consensus.get("risk_level", "HIGH"),
                "weighted_score": round(consensus.get("weighted_score", 0), 3),
                "should_trade": consensus.get("should_trade", False),
            }
            if indicator_errors:
                result["indicator_warnings"] = indicator_errors
            return result
        except Exception as e:
            self._last_analyze_error = str(e)
            logger.warning(f"Failed to analyze {symbol}: {e}")
            return None
