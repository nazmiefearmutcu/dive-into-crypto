package com.diveintocrypto.android.domain.model

/**
 * Five-level signal — verbatim from the original Python reference implementation
 */
enum class Signal(val score: Int) {
    STRONG_BUY(2),
    BUY(1),
    NEUTRAL(0),
    SELL(-1),
    STRONG_SELL(-2),
}
