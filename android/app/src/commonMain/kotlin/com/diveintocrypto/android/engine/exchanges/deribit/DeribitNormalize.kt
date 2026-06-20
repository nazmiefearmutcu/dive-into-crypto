package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.*
import kotlinx.serialization.json.*

/** Deribit WS message → canonical records. Port of `crypcodile/exchanges/deribit/normalize.py`. */
object DeribitNormalize {
    private const val MS_TO_NS = 1_000_000L
    private const val EX = "deribit"

    fun normalizeMessage(msg: JsonObject, localTs: Long): List<Record> {
        val params = msg["params"] as? JsonObject ?: return emptyList()
        val channel = params["channel"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val data = params["data"]
        return when {
            channel.startsWith("trades.") -> normTrades(data, localTs)
            channel.startsWith("book.") -> normBook(data, localTs)
            channel.startsWith("ticker.") -> normTicker(data, localTs)
            else -> emptyList()
        }
    }

    private fun JsonObject.dbl(k: String): Double? = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.lng(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
    private fun side(dir: String?): Side = when (dir) { "buy" -> Side.BUY; "sell" -> Side.SELL; else -> Side.UNKNOWN }
    private fun iv(v: Double?): Double? = v?.let { it / 100.0 }
    private fun canon(sym: String) = "$EX:$sym"

    private fun normTrades(data: JsonElement?, localTs: Long): List<Record> {
        val arr = data as? JsonArray ?: return emptyList()
        val out = ArrayList<Record>()
        for (el in arr) {
            val t = el as? JsonObject ?: continue
            val sym = t.str("instrument_name") ?: continue
            val ts = (t.lng("timestamp") ?: continue) * MS_TO_NS
            val s = side(t.str("direction"))
            val liqEl = t["liquidation"]
            val liq = liqEl != null && liqEl != JsonNull
            out.add(Trade(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                id = t.str("trade_id"), price = t.dbl("price") ?: 0.0,
                amount = t.dbl("amount") ?: 0.0, side = s, liquidation = liq))
            if (liq) out.add(Liquidation(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                price = t.dbl("price") ?: 0.0, amount = t.dbl("amount") ?: 0.0, side = s, id = t.str("trade_id")))
        }
        return out
    }

    private fun levels(arr: JsonElement?): List<Level> {
        val a = arr as? JsonArray ?: return emptyList()
        return a.mapNotNull { row ->
            val r = row as? JsonArray ?: return@mapNotNull null
            val action = r[0].jsonPrimitive.contentOrNull
            val price = r[1].jsonPrimitive.doubleOrNull ?: 0.0
            val amount = if (action == "delete") 0.0 else (r[2].jsonPrimitive.doubleOrNull ?: 0.0)
            Level(price, amount)
        }
    }

    private fun normBook(data: JsonElement?, localTs: Long): List<Record> {
        val d = data as? JsonObject ?: return emptyList()
        val sym = d.str("instrument_name") ?: return emptyList()
        val ts = (d.lng("timestamp") ?: return emptyList()) * MS_TO_NS
        val bids = levels(d["bids"]); val asks = levels(d["asks"])
        return if (d.str("type") == "snapshot") {
            val bidsN = (d["bids"] as? JsonArray)?.size ?: 0
            val asksN = (d["asks"] as? JsonArray)?.size ?: 0
            listOf(BookSnapshot(Venue.DERIBIT, canon(sym), sym, ts, localTs, bids, asks,
                depth = bidsN + asksN, seqId = d.lng("change_id")))
        } else {
            listOf(BookDelta(Venue.DERIBIT, canon(sym), sym, ts, localTs, bids, asks,
                seqId = d.lng("change_id"), prevSeqId = d.lng("prev_change_id")))
        }
    }

    private fun normTicker(data: JsonElement?, localTs: Long): List<Record> {
        val td = data as? JsonObject ?: return emptyList()
        val sym = td.str("instrument_name") ?: return emptyList()
        val ts = (td.lng("timestamp") ?: return emptyList()) * MS_TO_NS
        val greeks = td["greeks"] as? JsonObject
        val isOption = greeks != null || td["mark_iv"] != null
        if (isOption) {
            val parsed = DeribitSymbols.parseOptionSymbol(sym) ?: return emptyList()
            return listOf(OptionsChain(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                underlying = parsed.underlying, underlyingPrice = td.dbl("underlying_price"),
                strike = parsed.strike, expiry = parsed.expiryNs, optType = parsed.optType,
                markPrice = td.dbl("mark_price"), markIv = iv(td.dbl("mark_iv")),
                bidPx = td.dbl("best_bid_price"), bidSz = td.dbl("best_bid_amount"), bidIv = iv(td.dbl("bid_iv")),
                askPx = td.dbl("best_ask_price"), askSz = td.dbl("best_ask_amount"), askIv = iv(td.dbl("ask_iv")),
                lastPrice = td.dbl("last_price"), openInterest = td.dbl("open_interest"),
                delta = greeks?.dbl("delta"), gamma = greeks?.dbl("gamma"), vega = greeks?.dbl("vega"),
                theta = greeks?.dbl("theta"), rho = greeks?.dbl("rho")))
        }
        val out = ArrayList<Record>()
        out.add(DerivativeTicker(Venue.DERIBIT, canon(sym), sym, ts, localTs,
            lastPrice = td.dbl("last_price"), markPrice = td.dbl("mark_price"), indexPrice = td.dbl("index_price"),
            fundingRate = td.dbl("current_funding"), predictedFundingRate = td.dbl("funding_8h"),
            openInterest = td.dbl("open_interest")))
        val cf = td.dbl("current_funding")
        if (cf != null) out.add(Funding(Venue.DERIBIT, canon(sym), sym, ts, localTs,
            fundingRate = cf, predictedFundingRate = td.dbl("funding_8h"), fundingTs = ts, intervalHours = 8))
        return out
    }
}
