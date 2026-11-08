package com.diveintocrypto.android.widget

import com.diveintocrypto.android.ui.i18n.DiveStrings
import com.diveintocrypto.android.ui.i18n.EnStrings
import com.diveintocrypto.android.ui.i18n.TrStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VerdictBiasLabelTest {

    @Test
    fun verdictTokensMapToDirectionOnlyWordingInBothLanguages() {
        val languages: List<DiveStrings> = listOf(TrStrings, EnStrings)
        for (s in languages) {
            assertEquals(s.biasLong, verdictBiasLabel("BUY", s))
            assertEquals(s.biasLong, verdictBiasLabel("STRONG_BUY", s))
            assertEquals(s.biasShort, verdictBiasLabel("SELL", s))
            assertEquals(s.biasShort, verdictBiasLabel("STRONG_SELL", s))
            assertEquals(s.biasNone, verdictBiasLabel("NEUTRAL", s))
            assertFalse(verdictBiasLabel("BUY", s).contains("BUY"))
            assertFalse(verdictBiasLabel("SELL", s).contains("SELL"))
        }
    }
}
