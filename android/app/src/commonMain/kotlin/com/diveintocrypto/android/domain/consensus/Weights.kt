package com.diveintocrypto.android.domain.consensus

/**
 * Indicator weights — verbatim from the original Python reference implementation.
 * Covers ALL 57 indicators of the full pipeline (15 core + 42 extended).
 *
 * CANONICAL SOURCE OF TRUTH for default weights. Previously this map lived in
 * `AppContainer` but nothing consumed it — [Scorer.compute] fell back to `?: 1.0`,
 * so all 42 extended indicators silently scored at weight 1.0 and the README's
 * weighted-consensus claim was false. It is now wired into [SettingsStore]
 * defaults and [ConsensusEngine] construction.
 */
val ALL_INDICATOR_WEIGHTS: Map<String, Double> = mapOf(
    "rsi" to 1.5,
    "macd" to 2.0,
    "bollinger" to 1.5,
    "ema_cross" to 1.8,
    "sma_cross" to 1.8,
    "stochastic" to 1.2,
    "adx_di" to 1.5,
    "cci" to 1.0,
    "williams_r" to 1.0,
    "roc" to 1.0,
    "mfi" to 1.2,
    "atr_filter" to 0.0,
    "ichimoku" to 2.0,
    "psar" to 1.3,
    "obv" to 1.2,
    // ── Extended set (parity with the desktop reference engine, 2026-07-20) ──
    "supertrend" to 2.0,
    "awesome_oscillator" to 1.2,
    "cmf" to 1.5,
    "squeeze" to 2.5,
    "choppiness" to 1.0,
    "vwap" to 1.8,
    "vortex" to 1.5,
    "keltner_breakout" to 1.5,
    "donchian_breakout" to 1.5,
    "chaikin_oscillator" to 1.3,
    "elder_ray" to 1.2,
    "klinger_oscillator" to 1.3,
    "trix" to 1.4,
    "coppock_curve" to 1.2,
    "kst" to 1.4,
    "dpo" to 1.0,
    "fisher_transform" to 1.2,
    "connors_rsi" to 1.2,
    "stoch_rsi" to 1.2,
    "ultimate_oscillator" to 1.2,
    "aroon_oscillator" to 1.3,
    "schaff_trend_cycle" to 1.4,
    "wavetrend" to 1.5,
    "relative_vigor_index" to 1.1,
    "balance_of_power" to 1.0,
    "accum_dist_line" to 1.3,
    "mass_index" to 1.0,
    "cmo" to 1.2,
    "tsi" to 1.3,
    "vwma_cross" to 1.4,
    "qstick" to 1.0,
    "force_index" to 1.2,
    "bollinger_percent_b" to 1.2,
    "zscore_reversion" to 1.0,
    "linreg_slope" to 1.4,
    "atr_percentile" to 1.0,
    "hist_vol_percentile" to 0.8,
    "hurst" to 1.2,
    "range_expansion" to 1.0,
    "kalman_trend" to 1.4,
    "half_life_reversion" to 1.0,
    "rolling_sharpe" to 1.2,
)

/**
 * The FULL default weight map actually applied by the consensus engine.
 *
 * Precedence rule: the 15 CORE names keep the F2 consensus matrix values
 * ([DEFAULT_F2_WEIGHTS] — the weights production has always applied via
 * SettingsStore, so existing consensus behaviour for the core set is unchanged),
 * while the 42 EXTENDED names take the desktop-reference weights from
 * [ALL_INDICATOR_WEIGHTS]. The result has exactly 57 entries.
 *
 * `ALL_INDICATOR_WEIGHTS + DEFAULT_F2_WEIGHTS` — right operand wins, so the
 * F2 core overrides the 4 core names on which the two sources differ
 * (sma_cross 1.8→1.5, ichimoku 2.0→1.5, psar 1.3→1.2, obv 1.2→1.5).
 */
val DEFAULT_FULL_WEIGHTS: Map<String, Double> = ALL_INDICATOR_WEIGHTS + DEFAULT_F2_WEIGHTS
