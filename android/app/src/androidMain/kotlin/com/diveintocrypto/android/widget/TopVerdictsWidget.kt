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
import androidx.glance.layout.Box
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

/**
 * "Top Kararlar" home-screen widget: the LAST BACKGROUND SCAN's top-3 survivors
 * (symbol, verdict label, confidence) plus a data-age line ("<x dk önce" /
 * "veri yok"). All strings inline TR for now (the i18n lane comes later).
 *
 * Updates piggyback on [com.diveintocrypto.android.worker.DiveScanWorker]:
 * every worker run rewrites [WidgetScanState] and calls [updateAll]. There is
 * deliberately NO promise of fresh data — the age line is always rendered from
 * the real scan timestamp.
 */
class TopVerdictsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetScanState.load(createAppContainer(context).settingsStore)
        provideContent { Content(state) }
    }

    @Composable
    private fun Content(state: WidgetScanState?) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Bg).padding(10.dp),
        ) {
            Text(
                text = "En İyi Kararlar",
                style = TextStyle(color = ColorProvider(Accent), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(6.dp))

            val items = state?.items.orEmpty()
            if (state == null || items.isEmpty()) {
                Text(
                    text = "veri yok",
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
                            text = row.verdict,
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
            Text(
                text = ageLine(state?.scannedAtMs),
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }

    /** "<x dk önce" — honest minutes since the scan; null timestamp → "veri yok". */
    internal fun ageLine(scannedAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (scannedAtMs == null || scannedAtMs <= 0L) return "veri yok"
        val minutes = ((nowMs - scannedAtMs) / 60_000L).coerceAtLeast(1L)
        return "$minutes dk önce"
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

/** Manifest receiver (android:exported=false; only the system may update it). */
class TopVerdictsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopVerdictsWidget()
}
