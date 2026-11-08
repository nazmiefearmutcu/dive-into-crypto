package com.diveintocrypto.android.ui.i18n

import com.diveintocrypto.android.domain.alerts.AlertCondition
import com.diveintocrypto.android.domain.alerts.AlertEvaluator
import com.diveintocrypto.android.domain.alerts.AlertInputs
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.domain.alerts.AlertVerdict
import com.diveintocrypto.android.domain.alerts.EnglishAlertMessages
import com.diveintocrypto.android.domain.consensus.EnglishConsensusLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The DiveStrings-backed formatters must (a) keep the English defaults byte-identical
 * to the domain fallbacks and (b) produce the Turkish wording for the TR catalog.
 */
class DiveFormattersTest {

    @Test
    fun englishCatalogMatchesEnglishConsensusLabels() {
        val en = consensusLabelsFor(EnStrings)
        assertEquals(
            EnglishConsensusLabels.reasonLine("BUY", 40, "LOW", 2, 1, 2, "0.432", listOf("a", "b")),
            en.reasonLine("BUY", 40, "LOW", 2, 1, 2, "0.432", listOf("a", "b")),
        )
        assertEquals(EnglishConsensusLabels.highAtrVolatility(), en.highAtrVolatility())
        assertEquals(EnglishConsensusLabels.lowAtrVolatility(), en.lowAtrVolatility())
        assertEquals(EnglishConsensusLabels.weakTrend("12.0", "15"), en.weakTrend("12.0", "15"))
        assertEquals(EnglishConsensusLabels.highSignalConflict("0.45"), en.highSignalConflict("0.45"))
        assertEquals(EnglishConsensusLabels.moderateSignalConflict("0.35"), en.moderateSignalConflict("0.35"))
        assertEquals(EnglishConsensusLabels.fewActiveSignals(2, 4), en.fewActiveSignals(2, 4))
        assertEquals(EnglishConsensusLabels.weakConviction("0.120"), en.weakConviction("0.120"))
        assertEquals(EnglishConsensusLabels.whaleRetailDivergence(), en.whaleRetailDivergence())
        assertEquals(EnglishConsensusLabels.extremeFunding(), en.extremeFunding())
    }

    @Test
    fun turkishConsensusReasonUsesTurkishWordsAndRiskLabel() {
        val tr = consensusLabelsFor(TrStrings).reasonLine("BUY", 40, "HIGH", 2, 1, 2, "0.432", listOf("a"))
        assertTrue(tr.startsWith("Sinyal=BUY Güven=40% Risk=YÜKSEK"), tr)
        assertTrue(tr.endsWith("Riskler: a"), tr)
    }

    @Test
    fun multimodalNarrativeCatalogsHaveEveryCellInBothLanguages() {
        for (s in listOf(TrStrings, EnStrings)) {
            assertEquals(27, s.i18nNarrReading.size)
            assertEquals(27, s.i18nNarrReadingMirror.size)
            assertEquals(27, s.i18nNarrBias.size)
            assertEquals(27, s.i18nNarrBiasMirror.size)
        }
        assertEquals(EnStrings.i18nNarrReading[26], consensusLabelsFor(EnStrings).narrativeReading(26, mirrored = false))
    }

    @Test
    fun turkishMultimodalExplanationIsLocalizedAndEnglishKeepsItsFormat() {
        val tr = consensusLabelsFor(TrStrings).multimodalExplanation("CONTINUATION_TREND", 40, "x", "y", "1.00")
        assertTrue(tr.startsWith("Rejim=trend devamı Güven=40%"), tr)
        assertFalse(tr.contains("Regime="), tr)
        val en = EnglishConsensusLabels.multimodalExplanation("CONTINUATION_TREND", 40, "x", "y", "1.00")
        assertEquals("Regime=trend continuation Conf=40% Bias=x Reading=y Score=1.00", en)
    }

    @Test
    fun englishAlertCatalogMatchesDefaultMessages() {
        val en = alertMessagesFor(EnStrings)
        assertEquals(EnglishAlertMessages.priceAbove("BTCUSDT", 100.0, 101.5), en.priceAbove("BTCUSDT", 100.0, 101.5))
        assertEquals(EnglishAlertMessages.oiCollapse("ETHUSDT", -12.0, -10.0), en.oiCollapse("ETHUSDT", -12.0, -10.0))
        assertEquals(EnglishAlertMessages.oiExpansion("ETHUSDT", 5.0, 3.0), en.oiExpansion("ETHUSDT", 5.0, 3.0))
        assertEquals(EnglishAlertMessages.conditionJoiner(), en.conditionJoiner())
    }

    @Test
    fun alertEvaluatorUsesTheSuppliedLanguage() {
        val rule = AlertRule(
            id = "r1",
            symbol = "BTCUSDT",
            kind = AlertKind.PRICE_ABOVE,
            direction = AlertRule.DIRECTION_ANY,
            threshold = 100.0,
            oneShot = false,
            enabled = true,
            createdTs = 0L,
            conditions = listOf(AlertCondition(AlertKind.PRICE_ABOVE, AlertRule.DIRECTION_ANY, 100.0)),
            coalesceMs = 0L,
        )
        val inputs = AlertInputs(
            prices = mapOf("BTCUSDT" to 101.0),
            verdicts = mapOf("BTCUSDT" to AlertVerdict("BUY", 60, 101.0)),
        )
        val tr = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 1_000L, messages = alertMessagesFor(TrStrings))
        assertEquals("BTCUSDT fiyat 100.0 üzerinde (şimdi 101.0)", tr.fired.single().message)

        val en = AlertEvaluator.evaluate(listOf(rule), inputs, nowMs = 1_000L)
        assertEquals("BTCUSDT price above 100.0 (now 101.0)", en.fired.single().message)
    }

    @Test
    fun turkishAlertJoinerIsVe() {
        assertEquals(" VE ", alertMessagesFor(TrStrings).conditionJoiner())
    }
}
