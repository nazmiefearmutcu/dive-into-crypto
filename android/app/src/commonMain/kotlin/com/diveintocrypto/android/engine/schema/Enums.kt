package com.diveintocrypto.android.engine.schema

/** Trading venue. `wire` matches Crypcodile's exchange key (`schema/enums.py`). */
enum class Venue(val wire: String) {
    DERIBIT("deribit"),
    BINANCE_SPOT("binance-spot"),
    BINANCE_USDM("binance-usdm"),
    BYBIT("bybit"),
    OKX("okx"),
    COINBASE("coinbase");

    companion object {
        fun fromWire(s: String): Venue? = entries.firstOrNull { it.wire == s }
    }
}

/** Trade aggressor side. */
enum class Side {
    BUY, SELL, UNKNOWN;

    companion object {
        /** Binance aggTrade `m` flag: buyer is market maker ⇒ the taker SOLD. */
        fun fromBuyerMaker(buyerIsMaker: Boolean): Side = if (buyerIsMaker) SELL else BUY
    }
}

/** Option right. */
enum class OptType(val wire: String) {
    CALL("C"), PUT("P");

    companion object {
        fun fromWire(s: String): OptType? = entries.firstOrNull { it.wire == s }
    }
}

/** Canonical channel keys — match Crypcodile's tagged record channels. */
enum class Channel(val wire: String) {
    TRADE("trade"),
    BOOK_SNAPSHOT("book_snapshot"),
    BOOK_DELTA("book_delta"),
    BOOK_TICKER("book_ticker"),
    DERIVATIVE_TICKER("derivative_ticker"),
    OPTIONS_CHAIN("options_chain"),
    FUNDING("funding"),
    OPEN_INTEREST("open_interest"),
    LIQUIDATION("liquidation"),
    OHLCV("ohlcv");

    companion object {
        fun fromWire(s: String): Channel? = entries.firstOrNull { it.wire == s }
    }
}
