package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

abstract class BaseIndicator(protected val config: IndicatorConfig) {
    abstract val name: String

    /**
     * Compute the indicator on the given candle series (chronological, oldest first).
     * The last element is the most recent candle. Implementations must be stateless
     * and side-effect-free.
     */
    abstract fun calculate(candles: List<Candle>): IndicatorResult

    protected fun result(
        signal: Signal,
        reason: String,
        raw: Map<String, Double?> = emptyMap(),
    ): IndicatorResult = IndicatorResult(name = name, signal = signal, reason = reason, rawValues = raw)
}
