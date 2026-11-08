package com.diveintocrypto.android.ui.performance

import com.diveintocrypto.android.platform.format
import kotlin.math.floor
import kotlin.math.log10

/** Significant digits kept for a price below 1 (0.00012345 -> "0.00012345"). */
private const val SIGNIFICANT_DIGITS = 4

/** Ceiling on decimals so Double.format's Long scaling cannot overflow. */
private const val MAX_DECIMALS = 12

/**
 * Decimal places that keep [SIGNIFICANT_DIGITS] significant digits for a price below 1.
 * A fixed 4-decimal rounding showed 0.00004 as "0.0000". Never returns fewer than
 * [minDecimals]; prices of 1 or more return [minDecimals] unchanged.
 */
internal fun sigDecimalsBelowOne(price: Double, minDecimals: Int): Int {
    if (!(price > 0.0) || price >= 1.0) return minDecimals
    val leadingZeros = floor(-log10(price)).toInt()
    return (leadingZeros + SIGNIFICANT_DIGITS).coerceIn(minDecimals, MAX_DECIMALS)
}

/** Quote price text: 4 decimals (grouped) from 1 upward, significant digits below 1. */
internal fun formatQuotePrice(price: Double): String =
    if (price >= 1.0 || !(price > 0.0)) {
        price.format(4, grouped = true)
    } else {
        price.format(sigDecimalsBelowOne(price, 4))
    }
