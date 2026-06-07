package com.diveintocrypto.android.testutil

import com.diveintocrypto.android.domain.model.Candle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Multiplatform fixture loader. Parses the BTCUSDT 1h x300 reference data from
 * embedded constants ([BTCUSDT_1H_300_CSV] / [BTCUSDT_1H_300_EXPECTED_JSON])
 * rather than JVM classpath resources, so the fixture-pinned indicator tests
 * run on every KMP target (including iOS). The `resource` params are retained
 * for call-site compatibility but ignored.
 */
object FixtureLoader {

    fun loadCandles(resource: String = "fixtures/btcusdt_1h_300.csv"): List<Candle> {
        val lines = BTCUSDT_1H_300_CSV.lines().filter { it.isNotBlank() }
        // header: open_time,open,high,low,close,volume,close_time
        return lines.drop(1).map { row ->
            val c = row.split(",")
            Candle(
                openTime = c[0].toLong(),
                open = c[1].toDouble(),
                high = c[2].toDouble(),
                low = c[3].toDouble(),
                close = c[4].toDouble(),
                volume = c[5].toDouble(),
                closeTime = c[6].toLong(),
            )
        }
    }

    fun expectedFor(indicator: String, resource: String = "fixtures/btcusdt_1h_300_expected.json"): Expected {
        val root = Json.parseToJsonElement(BTCUSDT_1H_300_EXPECTED_JSON).jsonObject
        val node = root[indicator]?.jsonObject ?: error("Missing fixture key: $indicator")
        return Expected(
            signal = node["signal"]!!.jsonPrimitive.content,
            score = node["score"]!!.jsonPrimitive.content.toInt(),
            reason = node["reason"]!!.jsonPrimitive.content,
            rawValues = (node["raw_values"] as JsonObject).mapValues { (_, v) ->
                v.jsonPrimitive.doubleOrNull
            },
        )
    }

    data class Expected(
        val signal: String,
        val score: Int,
        val reason: String,
        val rawValues: Map<String, Double?>,
    )
}
