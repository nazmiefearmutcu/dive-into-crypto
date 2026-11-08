package com.diveintocrypto.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.diveintocrypto.android.data.createAppContainer
import com.diveintocrypto.android.ui.i18n.DiveStrings

/**
 * "Top Kararlar" home-screen widget: the LAST BACKGROUND SCAN's top-3 survivors
 * (symbol, verdict label, confidence) plus a data-age line and, when the run did
 * not cover the whole planned universe, a coverage note. Every visible string
 * comes from the app's [DiveStrings] catalog (the stored language), and the
 * not-investment-advice footer is always shown.
 *
 * Updates piggyback on [com.diveintocrypto.android.worker.DiveScanWorker]:
 * every worker run rewrites [WidgetScanState] and calls [updateAll]. There is
 * deliberately NO promise of fresh data — the age line is always rendered from
 * the real scan timestamp.
 */
class TopVerdictsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = createAppContainer(context)
        val state = WidgetScanState.load(container.settingsStore)
        val strings = container.language.value
        provideContent { Content(state, strings) }
    }

    @Composable
    private fun Content(state: WidgetScanState?, strings: DiveStrings) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Bg).padding(10.dp),
        ) {
            Text(
                text = strings.platWidgetTitle,
                style = TextStyle(color = ColorProvider(Accent), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(6.dp))

            val items = state?.items.orEmpty()
            if (state == null || items.isEmpty()) {
                Text(
                    text = strings.platWidgetNoData,
                    style = TextStyle(color = ColorProvider(TextDim), fontSize = 12.sp),
                )
            } else {
                items.forEach { row ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.symbol,
                            style = TextStyle(color = ColorProvider(TextBright), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            text = verdictBiasLabel(row.verdict, strings),
                            style = TextStyle(
                                color = ColorProvider(verdictColor(row.verdict)),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(modifier = GlanceModifier.padding(horizontal = 4.dp))
                        Text(
                            text = "%${row.confidence}",
                            style = TextStyle(color = ColorProvider(TextBright), fontSize = 11.sp),
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.defaultWeight())
            val age = ageLine(state?.scannedAtMs, strings)
            val ageText = if (state != null && state.partial) {
                "$age · ${strings.platWidgetPartial(state.okFetches, state.plannedFetches)}"
            } else {
                age
            }
            Text(
                text = ageText,
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 10.sp),
                maxLines = 1,
            )
            Text(
                text = strings.disclaimerShort,
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }

    /** Data-age line — honest minutes since the scan; null timestamp → no-data label. */
    internal fun ageLine(
        scannedAtMs: Long?,
        strings: DiveStrings,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (scannedAtMs == null || scannedAtMs <= 0L) return strings.platWidgetNoData
        val minutes = ((nowMs - scannedAtMs) / 60_000L).coerceAtLeast(1L)
        return strings.platWidgetAgo(minutes)
    }

    private fun verdictColor(verdict: String): Color = when (verdict) {
        "STRONG_BUY" -> Green
        "BUY" -> Green
        "STRONG_SELL" -> Red
        "SELL" -> Red
        else -> TextDim
    }

    companion object {
        private val Bg = Color(0xFF0B1220)
        private val Accent = Color(0xFF6EA8FE)
        private val TextBright = Color(0xFFE6EDF7)
        private val TextDim = Color(0xFF8A94A6)
        private val Green = Color(0xFF3FB68B)
        private val Red = Color(0xFFE5534B)
    }
}

/**
 * Direction-only label for a stored verdict token. The raw BUY/SELL names are never shown as a
 * headline on Android surfaces (Play copy policy); the token stays the data, the label is wording.
 * Used by the widget and the share card, so both carry the same neutral bias text.
 */
internal fun verdictBiasLabel(verdict: String, strings: DiveStrings): String = when (verdict) {
    "STRONG_BUY", "BUY" -> strings.biasLong
    "STRONG_SELL", "SELL" -> strings.biasShort
    else -> strings.biasNone
}

/** Manifest receiver (android:exported=false; only the system may update it). */
class TopVerdictsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopVerdictsWidget()
}
