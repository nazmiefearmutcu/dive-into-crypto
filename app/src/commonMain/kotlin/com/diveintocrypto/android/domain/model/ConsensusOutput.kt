package com.diveintocrypto.android.domain.model

data class SignalDetail(
    val name: String,
    val signal: Signal,
    val weight: Double,
    val weightedScore: Double,
    val reason: String,
)

data class ConsensusOutput(
    val finalSignal: Signal,
    val confidence: Int,
    val weightedScore: Double,
    val buyCount: Int,
    val sellCount: Int,
    val neutralCount: Int,
    val activeSignals: Int,
    val totalSignals: Int,
    val signalDetails: List<SignalDetail>,
    val reason: String,
    val shouldTrade: Boolean,
    val riskLevel: String = "UNKNOWN",
    val riskScore: Int = 0,
    val riskFactors: List<String> = emptyList(),
)
