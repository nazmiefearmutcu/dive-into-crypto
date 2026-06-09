package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.OptType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Parsed Deribit option symbol. */
data class ParsedOption(val underlying: String, val strike: Double, val expiryNs: Long, val optType: OptType)

/** Deribit option symbol parsing — port of `_parse_option_symbol` in normalize.py. */
object DeribitSymbols {
    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
    )
    private val DATE_RE = Regex("^(\\d{1,2})([A-Z]{3})(\\d{2})$")

    /** Parse BASE-DdMMMyy-STRIKE-(C|P). Returns null if not a 4-part option symbol. */
    fun parseOptionSymbol(sym: String): ParsedOption? {
        val parts = sym.split("-")
        if (parts.size < 4) return null
        val underlying = parts[0]
        val strike = parts[2].toDoubleOrNull() ?: return null
        val optType = when (parts[3]) {
            "C" -> OptType.CALL
            "P" -> OptType.PUT
            else -> return null
        }
        val m = DATE_RE.find(parts[1].uppercase()) ?: return null
        val day = m.groupValues[1].toInt()
        val month = MONTHS[m.groupValues[2]] ?: return null
        val year = 2000 + m.groupValues[3].toInt()
        val epochSec = LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).epochSeconds
        return ParsedOption(underlying, strike, epochSec * 1_000_000_000L, optType)
    }
}
