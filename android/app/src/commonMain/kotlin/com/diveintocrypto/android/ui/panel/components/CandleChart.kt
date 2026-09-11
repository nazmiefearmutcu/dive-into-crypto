package com.diveintocrypto.android.ui.panel.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.domain.cvd.CvdBucket
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.ui.panel.PanelUiState
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveFonts
import kotlin.math.abs

/**
 * Panel candlestick chart — Depth Terminal aesthetic on a Compose Canvas:
 *   - green/red candles with wicks ([DiveColors.Green] / [DiveColors.Red])
 *   - EMA20 + EMA50 polylines ([DiveColors.Accent] / [DiveColors.Accent2];
 *     null warm-up values split each polyline into honest segments)
 *   - Bollinger band as a translucent fill between [PanelUiState.bbUpper]/[bbLower]
 *   - hairline grid + corner brackets, right-edge price axis (last · max · min,
 *     monospace = tabular numerics)
 *   - volume bars along the bottom at low opacity, direction-tinted
 *   - last-price dashed line + accent pill
 *   - CVD per-minute delta mini-histogram strip below (buy green / sell red)
 *
 * HONESTY: no candles → an explicit "VERİ YOK — yenile" state; a failed CVD
 * fetch says so instead of drawing fabricated zeros. All scale math lives in
 * the pure, unit-tested [ChartMath]. Landscape simply stretches (fit width).
 */

/** Upper bound on rendered candles — the viewport stays readable when the engine hands over more. */
private const val MAX_BARS = 150

@Composable
fun CandleChartCard(
    state: PanelUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DiveCard(
        title = "${state.activeSymbol} · ${state.timeframe.uppercase()} · FİYAT",
        modifier = modifier,
    ) {
        if (state.chartCandles.isEmpty()) {
            // Honest empty state — no fabricated placeholder candles.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.BgCardHover)
                    .clickable { onRefresh() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "VERİ YOK — yenile",
                    color = DiveColors.Warn,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
            return@DiveCard
        }

        LegendRow()

        Spacer(Modifier.height(6.dp))

        // Tail window: candles AND overlays are sliced to the same LAST n bars
        // so series stay index-aligned no matter how many the engine returned.
        val n = minOf(state.chartCandles.size, MAX_BARS)
        val candles = state.chartCandles.takeLast(n)
        val ema20 = state.ema20.takeLast(n)
        val ema50 = state.ema50.takeLast(n)
        val bbUpper = state.bbUpper.takeLast(n)
        val bbLower = state.bbLower.takeLast(n)
        val deltaSeries = state.deltaSeries
        val cvdUnavailable = state.cvdUnavailable

        val textMeasurer = rememberTextMeasurer()

        // ── Price chart (~260dp): candles + overlays + volume + axis ──
        Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPriceChart(candles, ema20, ema50, bbUpper, bbLower, textMeasurer)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CVD DELTA · 1 dk",
                color = DiveColors.TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(8.dp))
            if (cvdUnavailable) {
                Text(
                    text = "VERİ YOK (akış hatası)",
                    color = DiveColors.Warn,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // ── CVD delta strip (~48dp) ──
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (deltaSeries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(DiveColors.BgCardHover),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (cvdUnavailable) "CVD alınamadı" else "CVD verisi yok",
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) { drawCvdStrip(deltaSeries) }
            }
        }
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(color = DiveColors.Accent, label = "EMA20")
        LegendSwatch(color = DiveColors.Accent2, label = "EMA50")
        LegendSwatch(color = DiveColors.Purple.copy(alpha = 0.30f), label = "BOLL", filled = true)
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, filled: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = if (filled) 8.dp else 3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = DiveColors.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Canvas drawing (pure DrawScope functions — all math via ChartMath)
// ═══════════════════════════════════════════════════════════════════════

private fun DrawScope.drawPriceChart(
    candles: List<Candle>,
    ema20: List<Double?>,
    ema50: List<Double?>,
    bbUpper: List<Double?>,
    bbLower: List<Double?>,
    textMeasurer: TextMeasurer,
) {
    if (candles.isEmpty()) return
    val n = candles.size

    val labelW = 52.dp.toPx()
    val topPad = 6.dp.toPx()
    val plotRight = size.width - labelW
    val priceTop = topPad
    val priceBottom = size.height * 0.80f
    val volTop = size.height * 0.84f
    val volBottom = size.height - 2.dp.toPx()

    val range = ChartMath.chartPriceRange(candles, bbUpper, bbLower)
    fun yOf(v: Double): Float = ChartMath.scaleY(v, range, priceTop, priceBottom)
    fun xCenter(i: Int): Float = plotRight * (i + 0.5f) / n

    // ── Hairline grid: min / mid / max ────────────────────────────────
    listOf(range.min, (range.min + range.max) / 2.0, range.max).forEach { v ->
        val y = yOf(v)
        drawLine(DiveColors.Border, Offset(0f, y), Offset(plotRight, y), strokeWidth = 1f)
    }

    // ── Bollinger band translucent fill (null warm-up → skip runs) ────
    fun bbValueAt(i: Int): Pair<Double, Double>? {
        val u = bbUpper.getOrNull(i) ?: return null
        val l = bbLower.getOrNull(i) ?: return null
        return u to l
    }
    var bbStart = -1
    fun flushBbRun(endExclusive: Int) {
        if (bbStart < 0 || endExclusive - bbStart < 2) { bbStart = -1; return }
        val path = Path()
        for (i in bbStart until endExclusive) {
            val (u, _) = bbValueAt(i) ?: break
            val x = xCenter(i); val y = yOf(u)
            if (i == bbStart) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in (endExclusive - 1) downTo bbStart) {
            val (_, l) = bbValueAt(i) ?: break
            path.lineTo(xCenter(i), yOf(l))
        }
        path.close()
        drawPath(path, DiveColors.Purple.copy(alpha = 0.10f))
        bbStart = -1
    }
    for (i in 0 until n) {
        if (bbValueAt(i) != null) { if (bbStart < 0) bbStart = i } else flushBbRun(i)
    }
    flushBbRun(n)

    // ── Volume bars (bottom strip, low opacity, direction-tinted) ─────
    val maxVol = ChartMath.maxVolume(candles)
    if (maxVol > 0.0) {
        val volBarW = ((plotRight / n) * 0.6f).coerceAtLeast(1f)
        candles.forEachIndexed { i, c ->
            if (!c.volume.isFinite() || c.volume <= 0.0) return@forEachIndexed
            val h = (c.volume / maxVol * (volBottom - volTop)).toFloat().coerceAtLeast(1f)
            val up = c.close >= c.open
            val color = (if (up) DiveColors.Green else DiveColors.Red).copy(alpha = 0.30f)
            drawRect(color, Offset(xCenter(i) - volBarW / 2f, volBottom - h), Size(volBarW, h))
        }
    }

    // ── Candles: wick + body ──────────────────────────────────────────
    val bodyW = ((plotRight / n) * 0.6f).coerceIn(1f, 12.dp.toPx())
    candles.forEachIndexed { i, c ->
        val x = xCenter(i)
        val up = c.close >= c.open
        val color = if (up) DiveColors.Green else DiveColors.Red
        drawLine(color, Offset(x, yOf(c.high)), Offset(x, yOf(c.low)), strokeWidth = 1f)
        val bodyTop = yOf(maxOf(c.open, c.close))
        val bodyBottom = yOf(minOf(c.open, c.close))
        val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1f)
        drawRect(color, Offset(x - bodyW / 2f, bodyTop), Size(bodyW, bodyH))
    }

    // ── EMA polylines (contiguous non-null runs — null warm-up skips) ─
    fun drawSeries(values: List<Double?>, color: Color, strokeWidth: Float) {
        var start = -1
        fun flush(endExclusive: Int) {
            if (start < 0 || endExclusive - start < 2) { start = -1; return }
            val path = Path()
            for (i in start until endExclusive) {
                val v = values.getOrNull(i) ?: break
                val x = xCenter(i); val y = yOf(v)
                if (i == start) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = strokeWidth))
            start = -1
        }
        for (i in 0 until n) {
            if (values.getOrNull(i) != null) { if (start < 0) start = i } else flush(i)
        }
        flush(n)
    }
    drawSeries(ema20, DiveColors.Accent, 1.5.dp.toPx())
    drawSeries(ema50, DiveColors.Accent2, 1.5.dp.toPx())

    // ── Last-price dashed line ────────────────────────────────────────
    val last = candles.last().close
    val lastY = yOf(last).coerceIn(priceTop, priceBottom)
    drawLine(
        color = DiveColors.Accent.copy(alpha = 0.75f),
        start = Offset(0f, lastY),
        end = Offset(plotRight, lastY),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )

    // ── Right-edge price axis: last (pill) · max · min (tabular numerics) ─
    val decimals = ChartMath.axisDecimals(range)
    val axisStyle = TextStyle(
        color = DiveColors.TextMuted,
        fontSize = 9.sp,
        fontFamily = DiveFonts.Mono, // monospace = tabular numerics
    )
    fun drawAxisLabel(text: String, y: Float, accent: Boolean) {
        val layout = textMeasurer.measure(text, axisStyle)
        val ly = (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
        if (accent) {
            drawRoundRect(
                color = DiveColors.Accent,
                topLeft = Offset(plotRight + 2.dp.toPx(), ly - 2.dp.toPx()),
                size = Size(labelW - 4.dp.toPx(), layout.size.height + 4.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        drawText(textLayoutResult = layout, topLeft = Offset(plotRight + 4.dp.toPx(), ly))
    }
    drawAxisLabel(ChartMath.formatAxisPrice(range.max, decimals), priceTop, accent = false)
    drawAxisLabel(ChartMath.formatAxisPrice(range.min, decimals), priceBottom, accent = false)
    drawAxisLabel(ChartMath.formatAxisPrice(last, decimals), lastY, accent = true)

    // ── Depth Terminal corner brackets ────────────────────────────────
    val arm = 10.dp.toPx()
    val inset = 1f
    fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
        drawLine(DiveColors.BorderStrong, Offset(cx, cy), Offset(cx + dx * arm, cy), strokeWidth = 1f)
        drawLine(DiveColors.BorderStrong, Offset(cx, cy), Offset(cx, cy + dy * arm), strokeWidth = 1f)
    }
    bracket(inset, inset, 1f, 1f)
    bracket(plotRight - inset, inset, -1f, 1f)
    bracket(inset, size.height - inset, 1f, -1f)
    bracket(plotRight - inset, size.height - inset, -1f, -1f)
}

private fun DrawScope.drawCvdStrip(buckets: List<CvdBucket>) {
    if (buckets.isEmpty()) return
    val maxAbs = ChartMath.maxAbsDelta(buckets)
    if (maxAbs <= 0.0) return
    val midY = size.height / 2f
    drawLine(DiveColors.Border, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
    val slot = size.width / buckets.size
    val barW = (slot * 0.7f).coerceAtLeast(1f)
    val halfArea = size.height / 2f - 2.dp.toPx()
    buckets.forEachIndexed { i, b ->
        if (!b.delta.isFinite() || b.delta == 0.0) return@forEachIndexed
        val h = (abs(b.delta) / maxAbs * halfArea).toFloat().coerceAtLeast(1f)
        val x = i * slot + (slot - barW) / 2f
        val color = if (b.delta > 0) DiveColors.Green.copy(alpha = 0.8f) else DiveColors.Red.copy(alpha = 0.8f)
        drawRect(color, Offset(x, if (b.delta > 0) midY - h else midY), Size(barW, h))
    }
}
