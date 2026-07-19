package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the Python reference `linreg_slope.py`.
 *
 * Linear-Regression Slope + R² trend-quality gate. Fits an OLS line to the last
 * `period` closes (np.polyfit(x, y, 1) — closed form: slope = cov(x,y)/var(x)).
 * Direction comes from the slope, but a signal is only emitted when the fit
 * quality R² clears `r2_min` — a noisy, low-R² move stays NEUTRAL. Slope is
 * normalised to a % move over the window so the threshold is scale-free.
 * Causal (regression uses only the closed window).
 */
class LinregSlopeIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "linreg_slope"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val r2Min = config.getDouble("r2_min", 0.55)
        val strong = config.getDouble("strong_pct", 0.03)
        val weak = config.getDouble("weak_pct", 0.008)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "LinReg insufficient data")
        }

        val closes = candles.map { it.close }
        val n = closes.size
        val y = DoubleArray(period) { closes[n - period + it] }

        // np.polyfit(x, y, 1) with x = 0..period-1: OLS slope/intercept closed form.
        val xBar = (period - 1) / 2.0
        var yBar = 0.0
        for (v in y) yBar += v
        yBar /= period
        var num = 0.0
        var den = 0.0
        for (i in 0 until period) {
            val dx = i - xBar
            num += dx * (y[i] - yBar)
            den += dx * dx
        }
        val slope = num / den
        val intercept = yBar - slope * xBar

        var ssRes = 0.0
        var ssTot = 0.0
        for (i in 0 until period) {
            val yhat = slope * i + intercept
            ssRes += (y[i] - yhat) * (y[i] - yhat)
            ssTot += (y[i] - yBar) * (y[i] - yBar)
        }
        val r2 = if (ssTot > 0.0) 1.0 - ssRes / ssTot else 0.0
        // Python: `mean = y.mean() or 1.0` — falsy 0.0 falls back to 1.0.
        val mean = if (yBar == 0.0) 1.0 else yBar
        val slopePct = slope * period / mean // total % move implied over the window

        val raw = mapOf<String, Double?>(
            "slope_pct" to roundTo(slopePct, 5),
            "r2" to roundTo(r2, 4),
        )

        // Python `{slope_pct:+.2%}` / `{r2:.2f}`
        val pctStr = "${(slopePct * 100.0).format(2, plus = true)}%"
        val r2Str = r2.format(2)

        return when {
            r2 < r2Min -> result(Signal.NEUTRAL, "LinReg low fit R²=$r2Str", raw)
            slopePct >= strong -> result(Signal.STRONG_BUY, "LinReg up $pctStr R²=$r2Str", raw)
            slopePct >= weak -> result(Signal.BUY, "LinReg up $pctStr R²=$r2Str", raw)
            slopePct <= -strong -> result(Signal.STRONG_SELL, "LinReg down $pctStr R²=$r2Str", raw)
            slopePct <= -weak -> result(Signal.SELL, "LinReg down $pctStr R²=$r2Str", raw)
            else -> result(Signal.NEUTRAL, "LinReg flat R²=$r2Str", raw)
        }
    }

    private fun roundTo(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        var f = 1.0
        repeat(digits) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
