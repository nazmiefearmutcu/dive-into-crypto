package com.diveintocrypto.android.data.binance

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Every non-2xx answer from a market-data endpoint becomes an [UpstreamHttpException]
 * carrying the HTTP status, so the UI can tell a regional block (451/403) from a
 * rate limit (429/418) or an outage (5xx) instead of showing a raw string.
 */
internal fun requireUpstreamOk(endpoint: String, status: HttpStatusCode) {
    if (!status.isSuccess()) throw UpstreamHttpException(endpoint, status.value)
}

/**
 * A finite double at [key], or null when the field is absent, JSON null or malformed.
 * Callers drop the row (or fail) on null — a missing value is never zero-filled.
 */
internal fun JsonObject.finiteDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** A long at [key], or null when absent or malformed. */
internal fun JsonObject.longValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

/** A string at [key], or null when absent or JSON null. */
internal fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
