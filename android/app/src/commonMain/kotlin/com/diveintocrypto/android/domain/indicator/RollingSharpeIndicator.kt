package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1:1 port of the Python reference `rolling_sharpe.py`.
 *
 * Rolling Sharpe Momentum — risk-adjusted return direction. Sharpe over the
 * window = mean(logret) / std(logret) · √period (std is the sample std,
 * ddof=1). It rewards steady, low-volatility trends and penalises choppy
 * ones. Sign gives direction; magnitude gates strength. Causal.
 */
class RollingSharpeIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "rolling_sharpe"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val strong = config.getDouble("strong", 1.0)
        val weak = config.getDouble("weak", 0.3)

        val closes = candles.map { it.close }
        val n = closes.size
        if (n < period + 1 || closes.any { it <= 0.0 }) {
            return result(Signal.NEUTRAL, "Sharpe insufficient data")
        }

        // logret = np.diff(np.log(close))[-period:]
        val logret = DoubleArray(period) {
            val i = n - period + it
            ln(closes[i]) - ln(closes[i - 1])
        }
        var mu = 0.0
        for (v in logret) mu += v
        mu /= period
        var ss = 0.0
        for (v in logret) {
            val d = v - mu
            ss += d * d
        }
        val sd = sqrt(ss / (period - 1)) // std(ddof=1)
        if (sd == 0.0) {
            return result(Signal.NEUTRAL, "zero-variance window")
        }
        val sharpe = mu / sd * sqrt(period.toDouble())

        val raw = mapOf<String, Double?>("sharpe" to roundTo(sharpe, 4))

        // Python `{sharpe:+.2f}`
        val sStr = sharpe.format(2, plus = true)
        return when {
            sharpe >= strong -> result(Signal.STRONG_BUY, "Sharpe $sStr strong risk-adj up", raw)
            sharpe >= weak -> result(Signal.BUY, "Sharpe $sStr risk-adj up", raw)
            sharpe <= -strong -> result(Signal.STRONG_SELL, "Sharpe $sStr strong risk-adj down", raw)
            sharpe <= -weak -> result(Signal.SELL, "Sharpe $sStr risk-adj down", raw)
            else -> result(Signal.NEUTRAL, "Sharpe $sStr neutral", raw)
        }
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
