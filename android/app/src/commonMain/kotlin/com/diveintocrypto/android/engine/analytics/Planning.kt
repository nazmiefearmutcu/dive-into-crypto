package com.diveintocrypto.android.engine.analytics

/**
 * ATR%-based planning strip — 0.3.0 parity addition (pure math, unit-tested).
 *
 * Turns the ATR% the indicator layer already computes (atr_filter → atr_pct)
 * into an honest pre-trade plan for one direction:
 *   - stop   = entry ∓ slMult·ATR%   (adverse)
 *   - target = entry ± tpMult·ATR%   (favorable; 2R at the 1.5/3.0 defaults)
 *   - envelope = entry ± envMult·ATR% (the "stay in the band" hold range)
 * No position sizing, no execution promise — geometry only.
 */
object PlanningStrip {

    const val DIRECTION_LONG = "LONG"
    const val DIRECTION_SHORT = "SHORT"

    /** Default stop distance in ATR multiples. */
    const val SL_MULT: Double = 1.5

    /** Default target distance in ATR multiples (2R at the default SL). */
    const val TP_MULT: Double = 3.0

    /** Default hold-envelope width in ATR multiples (each side of entry). */
    const val ENV_MULT: Double = 2.0

    data class Plan(
        val entry: Double,
        val atrPct: Double,
        val direction: String,
        /** Stop-loss price (adverse side). */
        val slPrice: Double,
        /** Take-profit price (favorable side). */
        val tpPrice: Double,
        /** Hold-envelope floor (entry − envMult·ATR%). */
        val envLow: Double,
        /** Hold-envelope ceiling (entry + envMult·ATR%). */
        val envHigh: Double,
        /** Reward:risk of the SL/TP pair (tpMult/slMult). */
        val rr: Double,
    )

    /**
     * PURE plan from entry price + ATR% + direction.
     * null when the entry is non-positive, ATR% is negative, or the direction
     * is not LONG/SHORT (NEUTRAL gets no plan — honest).
     */
    fun build(
        entry: Double,
        atrPct: Double,
        direction: String,
        slMult: Double = SL_MULT,
        tpMult: Double = TP_MULT,
        envMult: Double = ENV_MULT,
    ): Plan? {
        if (entry <= 0.0 || !atrPct.isFinite() || atrPct < 0.0) return null
        val dir = when (direction.uppercase()) {
            DIRECTION_LONG -> 1
            DIRECTION_SHORT -> -1
            else -> return null
        }
        val slDist = atrPct * slMult / 100.0
        val tpDist = atrPct * tpMult / 100.0
        val envDist = atrPct * envMult / 100.0
        return Plan(
            entry = entry,
            atrPct = atrPct,
            direction = direction.uppercase(),
            slPrice = entry * (1.0 - dir * slDist),
            tpPrice = entry * (1.0 + dir * tpDist),
            envLow = entry * (1.0 - envDist),
            envHigh = entry * (1.0 + envDist),
            rr = tpMult / slMult,
        )
    }
}
