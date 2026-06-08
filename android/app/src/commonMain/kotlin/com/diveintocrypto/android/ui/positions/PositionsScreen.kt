package com.diveintocrypto.android.ui.positions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.formatTime
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Positions (market position data) screen — 3 real series from Binance futures
 * public APIs:
 *   1. Open Interest (OI) — how many USD of contracts are open
 *   2. Account Ratio    — top traders' long/short ratio by account count
 *   3. Position Ratio   — top traders' L/S ratio by position size
 *
 * For each series, the latest value + a 30-point sparkline trend is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(container: AppContainer) {
    val vm: PositionsViewModel = viewModel { PositionsViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        PageHeader(
            title = "Market Position Data",
            lastUpdateMs = state.lastUpdateMs,
            stale = state.lastUpdateMs?.let { (nowMillis() - it) > 5 * 60_000 } ?: false,
            onRefresh = { vm.refresh() },
        )

        // Symbol + period selectors
        SymbolPeriodBar(state = state, onPeriod = vm::selectPeriod)

        if (state.isLoading) {
            LoadingCard()
        }
        state.error?.let { ErrorCard(it) }

        if (!state.isLoading && state.error == null) {
            UnifiedPositionsCard(state = state)
        }
    }
    }
}

@Composable
private fun SymbolPeriodBar(state: PositionsUiState, onPeriod: (String) -> Unit) {
    DiveCard(title = "${state.activeSymbol} · ${state.period}") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.periods.forEach { p ->
                val active = p == state.period
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) DiveColors.Accent else DiveColors.BgCardHover)
                        .border(
                            1.dp,
                            if (active) DiveColors.Accent else DiveColors.Border,
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { onPeriod(p) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = p,
                        color = if (active) Color.White else DiveColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DiveFonts.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedPositionsCard(state: PositionsUiState) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var touchOffset by remember { mutableStateOf(Offset.Zero) }
    var isPressed by remember { mutableStateOf(false) }

    val prices = state.closePrices
    val oi = state.openInterest.map { it.sumOpenInterestValue }
    val globalRatio = state.globalRatio.map { it.longShortRatio }
    val accountRatio = state.accountRatio.map { it.longShortRatio }
    val positionRatio = state.positionRatio.map { it.longShortRatio }
    val takerRatio = state.takerRatio.map { it.buySellRatio }
    val netTakerVolume = state.netTakerVolume
    val fundingRate = state.fundingRate.map { it.fundingRate }
    val quantBias = state.quantBias

    if (prices.isEmpty() || oi.isEmpty() || globalRatio.isEmpty() || accountRatio.isEmpty() || positionRatio.isEmpty() || takerRatio.isEmpty() || netTakerVolume.isEmpty() || fundingRate.isEmpty() || quantBias.isEmpty()) {
        EmptyStateCard()
        return
    }

    val displayIndex = selectedIndex ?: (prices.size - 1)
    val curPrice = prices.getOrElse(displayIndex) { 0.0 }
    val curOi = oi.getOrElse(displayIndex) { 0.0 }
    val curGlobalRatio = globalRatio.getOrElse(displayIndex) { 0.0 }
    val curAccRatio = accountRatio.getOrElse(displayIndex) { 0.0 }
    val curPosRatio = positionRatio.getOrElse(displayIndex) { 0.0 }
    val curTakerRatio = takerRatio.getOrElse(displayIndex) { 0.0 }
    val curNetTakerVol = netTakerVolume.getOrElse(displayIndex) { 0.0 }
    val curFundingRate = fundingRate.getOrElse(displayIndex) { 0.0 }
    val curQuantBias = quantBias.getOrElse(displayIndex) { 0.0 }

    val priceDelta = getDeltaPct(prices, displayIndex)
    val oiDelta = getDeltaPct(oi, displayIndex)
    val globalDelta = getDeltaPct(globalRatio, displayIndex)
    val accDelta = getDeltaPct(accountRatio, displayIndex)
    val posDelta = getDeltaPct(positionRatio, displayIndex)
    val takerDelta = getDeltaPct(takerRatio, displayIndex)
    val netTakerDelta = getDeltaPct(netTakerVolume, displayIndex)
    val fundingDelta = getDeltaPct(fundingRate, displayIndex)

    val prevQuantBias = quantBias.getOrElse((displayIndex - 1).coerceIn(0, quantBias.size - 1)) { curQuantBias }
    val biasDelta = curQuantBias - prevQuantBias

    val biasColor = if (curQuantBias >= 20.0) DiveColors.Green else if (curQuantBias <= -20.0) DiveColors.Red else DiveColors.TextDim

    // Min / max (▼/▲) — writes the range the sparkline spans into the table.
    val priceRange = "▼${formatPrice(prices.min())} ▲${formatPrice(prices.max())}"
    val oiRange = "▼${formatBig(oi.min())} ▲${formatBig(oi.max())}"
    val biasRange = "▼${quantBias.min().format(1)}% ▲${quantBias.max().format(1)}%"
    val posRange = "▼${positionRatio.min().format(2)} ▲${positionRatio.max().format(2)}"
    val accRange = "▼${accountRatio.min().format(2)} ▲${accountRatio.max().format(2)}"
    val globalRange = "▼${globalRatio.min().format(2)} ▲${globalRatio.max().format(2)}"
    val takerRange = "▼${takerRatio.min().format(2)} ▲${takerRatio.max().format(2)}"
    val netTakerRange = "▼${formatBig(netTakerVolume.min())} ▲${formatBig(netTakerVolume.max())}"
    val fundingRange = "▼${(fundingRate.min() * 100.0).format(4)}% ▲${(fundingRate.max() * 100.0).format(4)}%"

    DiveCard(title = "${state.activeSymbol} UNIFIED QUANTITATIVE ANALYSIS") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isPressed && selectedIndex != null) {
                Text(
                    text = "Inspect Mode (Point: ${displayIndex + 1}/${prices.size})",
                    color = DiveColors.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body
                )
            } else {
                Text(
                    text = "Quantitative Indicators & Sentiment",
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body
                )
            }

            QuantSentimentGauge(score = curQuantBias)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val priceColor = when (state.priceChangeDirection) {
                        "UP" -> DiveColors.Green
                        "DOWN" -> DiveColors.Red
                        else -> DiveColors.Text
                    }
                    MiniValueCol(
                        label = "PRICE",
                        value = formatPrice(curPrice),
                        deltaPct = priceDelta,
                        color = if (priceDelta >= 0) DiveColors.Green else DiveColors.Red,
                        valueColor = priceColor,
                        rangeText = priceRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "OPEN INTEREST",
                        value = formatBig(curOi),
                        deltaPct = oiDelta,
                        color = DiveColors.Cyan,
                        rangeText = oiRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "QUANT BIAS",
                        value = "${curQuantBias.format(1, plus = true)}%",
                        deltaPct = biasDelta,
                        color = biasColor,
                        valueColor = biasColor,
                        rangeText = biasRange,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniValueCol(
                        label = "WHALE L/S",
                        value = "${curPosRatio.format(2)}",
                        deltaPct = posDelta,
                        color = DiveColors.Purple,
                        rangeText = posRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "ACCOUNT L/S",
                        value = "${curAccRatio.format(2)}",
                        deltaPct = accDelta,
                        color = DiveColors.Orange,
                        rangeText = accRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "GLOBAL L/S",
                        value = "${curGlobalRatio.format(2)}",
                        deltaPct = globalDelta,
                        color = Color(0xFF3B82F6),
                        rangeText = globalRange,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniValueCol(
                        label = "TAKER L/S",
                        value = "${curTakerRatio.format(2)}",
                        deltaPct = takerDelta,
                        color = Color(0xFF84CC16),
                        rangeText = takerRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "NET TAKER VOLUME",
                        value = formatBig(curNetTakerVol),
                        deltaPct = netTakerDelta,
                        color = Color(0xFFEC4899),
                        rangeText = netTakerRange,
                        modifier = Modifier.weight(1f)
                    )
                    MiniValueCol(
                        label = "FUNDING RATE",
                        value = "${(curFundingRate * 100.0).format(4)}%",
                        deltaPct = fundingDelta,
                        color = Color(0xFFFFD600),
                        rangeText = fundingRange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.Bg)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
                .pointerInput(prices.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            isPressed = true
                            touchOffset = offset
                            val w = size.width
                            if (w > 0) {
                                val maxIdx = (prices.size - 1).coerceAtLeast(1)
                                val idx = ((offset.x / w) * maxIdx).toInt().coerceIn(0, prices.size - 1)
                                selectedIndex = idx
                            }
                        },
                        onDragEnd = {
                            isPressed = false
                            selectedIndex = null
                        },
                        onDragCancel = {
                            isPressed = false
                            selectedIndex = null
                        },
                        onDrag = { change, dragAmount ->
                            touchOffset = change.position
                            val w = size.width
                            if (w > 0) {
                                val maxIdx = (prices.size - 1).coerceAtLeast(1)
                                val idx = ((change.position.x / w) * maxIdx).toInt().coerceIn(0, prices.size - 1)
                                selectedIndex = idx
                            }
                            change.consume()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gap = 12f
                // Unit budget: the price chart is large (priceUnits), the 8 metrics one unit each.
                val priceUnits = 4f
                val metricCount = 8
                val unit = (h - gap * (metricCount + 2)) / (priceUnits + metricCount)
                val priceHeight = unit * priceUnits
                val metricHeight = unit
                fun metricY(i: Int) = priceHeight + metricHeight * i + gap * (i + 2)

                // --- LARGE price chart: candle / heikin / line / area (per the chartType axis) ---
                val chartType = com.diveintocrypto.android.ui.theme.DiveThemeController.axes.chartType
                val renderedCandles = if (chartType == "heikin") heikinAshi(state.candles) else state.candles
                val candleMode = (chartType == "candle" || chartType == "heikin") && renderedCandles.size >= 2

                if (candleMode) {
                    drawCandles(
                        candles = renderedCandles,
                        yOffset = gap,
                        height = priceHeight,
                        width = w,
                        upColor = DiveColors.Green,
                        downColor = DiveColors.Red
                    )
                } else {
                    drawSubChart(
                        values = prices,
                        yOffset = gap,
                        height = priceHeight,
                        width = w,
                        lineColor = DiveColors.Chart,
                        fillColor = DiveColors.Chart.copy(alpha = 0.12f)
                    )
                }

                drawSubChart(oi, metricY(0), metricHeight, w, DiveColors.Cyan, DiveColors.Cyan.copy(alpha = 0.12f))
                drawSubChart(globalRatio, metricY(1), metricHeight, w, Color(0xFF3B82F6), Color(0xFF3B82F6).copy(alpha = 0.12f))
                drawSubChart(accountRatio, metricY(2), metricHeight, w, DiveColors.Orange, DiveColors.Orange.copy(alpha = 0.12f))
                drawSubChart(positionRatio, metricY(3), metricHeight, w, DiveColors.Purple, DiveColors.Purple.copy(alpha = 0.12f))
                drawSubChart(takerRatio, metricY(4), metricHeight, w, Color(0xFF84CC16), Color(0xFF84CC16).copy(alpha = 0.12f))
                drawSubChart(netTakerVolume, metricY(5), metricHeight, w, Color(0xFFEC4899), Color(0xFFEC4899).copy(alpha = 0.12f))
                drawSubChart(fundingRate, metricY(6), metricHeight, w, Color(0xFFFFD600), Color(0xFFFFD600).copy(alpha = 0.12f))
                drawQuantBiasChart(quantBias, metricY(7), metricHeight, w)

                if (isPressed && selectedIndex != null) {
                    val selIdx = selectedIndex!!
                    val maxIdx = (prices.size - 1).coerceAtLeast(1)
                    val cursorX = if (candleMode) {
                        val slot = w / renderedCandles.size
                        (selIdx + 0.5f) * slot
                    } else {
                        (selIdx.toFloat() / maxIdx) * w
                    }
                    val cursorY = touchOffset.y.coerceIn(0f, h)

                    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)

                    drawLine(
                        color = DiveColors.TextDim.copy(alpha = 0.6f),
                        start = Offset(cursorX, 0f),
                        end = Offset(cursorX, h),
                        strokeWidth = 2f,
                        pathEffect = pathEffect
                    )

                    drawLine(
                        color = DiveColors.TextDim.copy(alpha = 0.6f),
                        start = Offset(0f, cursorY),
                        end = Offset(w, cursorY),
                        strokeWidth = 2f,
                        pathEffect = pathEffect
                    )

                    val priceMarkerColor = if (priceDelta >= 0) DiveColors.Green else DiveColors.Red
                    if (candleMode) {
                        drawCandleCursorMarker(renderedCandles, selIdx, gap, priceHeight, w, priceMarkerColor)
                    } else {
                        drawCursorIntersectionMarker(prices, selIdx, gap, priceHeight, w, priceMarkerColor)
                    }
                    drawCursorIntersectionMarker(oi, selIdx, metricY(0), metricHeight, w, DiveColors.Cyan)
                    drawCursorIntersectionMarker(globalRatio, selIdx, metricY(1), metricHeight, w, Color(0xFF3B82F6))
                    drawCursorIntersectionMarker(accountRatio, selIdx, metricY(2), metricHeight, w, DiveColors.Orange)
                    drawCursorIntersectionMarker(positionRatio, selIdx, metricY(3), metricHeight, w, DiveColors.Purple)
                    drawCursorIntersectionMarker(takerRatio, selIdx, metricY(4), metricHeight, w, Color(0xFF84CC16))
                    drawCursorIntersectionMarker(netTakerVolume, selIdx, metricY(5), metricHeight, w, Color(0xFFEC4899))
                    drawCursorIntersectionMarker(fundingRate, selIdx, metricY(6), metricHeight, w, Color(0xFFFFD600))
                    drawCursorIntersectionMarker(quantBias, selIdx, metricY(7), metricHeight, w, biasColor)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val curAccPoint = state.accountRatio.getOrNull(displayIndex)
        val accLong = (curAccPoint?.longAccount ?: 0.0) * 100.0
        val accShort = (curAccPoint?.shortAccount ?: 0.0) * 100.0

        val curPosPoint = state.positionRatio.getOrNull(displayIndex)
        val posLong = (curPosPoint?.longAccount ?: 0.0) * 100.0
        val posShort = (curPosPoint?.shortAccount ?: 0.0) * 100.0

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "L/S Ratio Details (${if (isPressed) "Selected Point" else "Current"})",
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Whale L/S Distribution (Position-Weighted)", color = DiveColors.TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("L: ${posLong.format(1)}%", color = DiveColors.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("S: ${posShort.format(1)}%", color = DiveColors.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                LongShortBar(longPct = posLong, shortPct = posShort)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Account L/S Distribution (User Count)", color = DiveColors.TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("L: ${accLong.format(1)}%", color = DiveColors.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("S: ${accShort.format(1)}%", color = DiveColors.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                LongShortBar(longPct = accLong, shortPct = accShort)
            }
        }

        Spacer(Modifier.height(16.dp))
        HistoryTableCard(state = state, selectedIndex = selectedIndex, onSelectIndex = { selectedIndex = it })
    }
}

@Composable
private fun QuantSentimentGauge(score: Double) {
    val text = when {
        score >= 60.0 -> "STRONG BULL / STRONG BUY"
        score >= 20.0 -> "BULLISH BIAS / BUY"
        score > -20.0 -> "BALANCED / NEUTRAL"
        score > -60.0 -> "BEARISH BIAS / SELL"
        else -> "STRONG BEAR / STRONG SELL"
    }

    val color = when {
        score >= 20.0 -> DiveColors.Green
        score <= -20.0 -> DiveColors.Red
        else -> DiveColors.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "QUANTITATIVE MARKET DIRECTION SCORE (QUANT BIAS)",
                    color = DiveColors.TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = text,
                    color = color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body
                )
            }
            Text(
                text = "${score.format(1, plus = true)}%",
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(DiveColors.BgCardHover)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(2.dp)
                    .height(6.dp)
                    .background(DiveColors.Border)
            )

            val biasWeight = (score + 100.0) / 200.0
            Row(modifier = Modifier.fillMaxSize()) {
                if (score < 0) {
                    Spacer(modifier = Modifier.weight(biasWeight.toFloat().coerceAtLeast(0.001f)))
                    Box(
                        modifier = Modifier
                            .weight((0.5f - biasWeight.toFloat()).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(DiveColors.Red)
                    )
                    Spacer(modifier = Modifier.weight(0.5f))
                } else {
                    Spacer(modifier = Modifier.weight(0.5f))
                    Box(
                        modifier = Modifier
                            .weight((biasWeight.toFloat() - 0.5f).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(DiveColors.Green)
                    )
                    Spacer(modifier = Modifier.weight((1.0f - biasWeight.toFloat()).coerceAtLeast(0.001f)))
                }
            }
        }
    }
}

private fun DrawScope.drawQuantBiasChart(
    values: List<Double>,
    yOffset: Float,
    height: Float,
    width: Float
) {
    if (values.size < 2) return

    val center = yOffset + height / 2f

    val pts = values.mapIndexed { i, v ->
        val x = (i.toFloat() / (values.size - 1).coerceAtLeast(1)) * width
        val norm = (v / 100.0).toFloat().coerceIn(-1.0f, 1.0f)
        val y = center - norm * (height / 2f)
        Offset(x, y)
    }

    drawLine(
        color = DiveColors.Border.copy(alpha = 0.5f),
        start = Offset(0f, center),
        end = Offset(width, center),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )

    for (i in 0 until pts.size - 1) {
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val v1 = values[i]
        val v2 = values[i + 1]

        val fillPath = Path().apply {
            moveTo(p1.x, center)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p2.x, center)
            close()
        }

        val fillColor = if (v1 + v2 >= 0) {
            DiveColors.Green.copy(alpha = 0.15f)
        } else {
            DiveColors.Red.copy(alpha = 0.15f)
        }
        drawPath(path = fillPath, color = fillColor)

        val segmentColor = if (v1 + v2 >= 0) DiveColors.Green else DiveColors.Red
        drawLine(
            color = segmentColor,
            start = p1,
            end = p2,
            strokeWidth = 2.5f
        )
    }
}

@Composable
private fun MiniValueCol(
    label: String,
    value: String,
    deltaPct: Double,
    color: Color,
    valueColor: Color = DiveColors.Text,
    rangeText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body
            )
        }
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body
        )
        val sign = if (deltaPct > 0) "+" else ""
        val deltaColor = if (deltaPct > 0) DiveColors.Green else if (deltaPct < 0) DiveColors.Red else DiveColors.TextMuted
        Text(
            text = "${sign}${deltaPct.format(2)}%",
            color = deltaColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body
        )
        if (rangeText != null) {
            Text(
                text = rangeText,
                color = DiveColors.TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = DiveFonts.body,
                lineHeight = 11.sp
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    DiveCard(title = "LOADING ANALYSIS") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Preparing data...", color = DiveColors.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LongShortBar(longPct: Double, shortPct: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(DiveColors.BgCardHover),
    ) {
        when {
            longPct <= 0.0 && shortPct <= 0.0 -> {
                Box(modifier = Modifier.weight(1f).background(DiveColors.BgCardHover))
            }
            shortPct <= 0.0 -> {
                Box(modifier = Modifier.weight(1f).background(DiveColors.Green))
            }
            longPct <= 0.0 -> {
                Box(modifier = Modifier.weight(1f).background(DiveColors.Red))
            }
            else -> {
                Box(modifier = Modifier.weight(longPct.toFloat()).background(DiveColors.Green))
                Box(modifier = Modifier.weight(shortPct.toFloat()).background(DiveColors.Red))
            }
        }
    }
}

private fun DrawScope.drawSubChart(
    values: List<Double>,
    yOffset: Float,
    height: Float,
    width: Float,
    lineColor: Color,
    fillColor: Color
) {
    val valid = values.filter { !it.isNaN() }
    if (valid.size < 2) return

    val min = valid.min()
    val max = valid.max()
    val span = (max - min).takeIf { it > 0 } ?: 1.0

    val pts = valid.mapIndexed { i, v ->
        val x = (i.toFloat() / (valid.size - 1).coerceAtLeast(1)) * width
        val norm = ((v - min) / span).toFloat()
        val y = yOffset + height - norm * height
        Offset(x, y)
    }

    drawLine(
        color = DiveColors.Border.copy(alpha = 0.3f),
        start = Offset(0f, yOffset),
        end = Offset(width, yOffset),
        strokeWidth = 1f
    )
    drawLine(
        color = DiveColors.Border.copy(alpha = 0.3f),
        start = Offset(0f, yOffset + height),
        end = Offset(width, yOffset + height),
        strokeWidth = 1f
    )

    if (com.diveintocrypto.android.ui.theme.DiveThemeController.chartArea) {
        val path = Path().apply {
            moveTo(0f, yOffset + height)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(width, yOffset + height)
            close()
        }
        drawPath(path = path, color = fillColor)
    }

    for (i in 1 until pts.size) {
        drawLine(
            color = lineColor,
            start = pts[i - 1],
            end = pts[i],
            strokeWidth = 2.5f,
        )
    }
}

private fun DrawScope.drawCursorIntersectionMarker(
    values: List<Double>,
    selectedIndex: Int,
    yOffset: Float,
    height: Float,
    width: Float,
    color: Color
) {
    val valid = values.filter { !it.isNaN() }
    if (valid.size < 2 || selectedIndex >= valid.size) return

    val min = valid.min()
    val max = valid.max()
    val span = (max - min).takeIf { it > 0 } ?: 1.0

    val v = valid[selectedIndex]
    val x = (selectedIndex.toFloat() / (valid.size - 1).coerceAtLeast(1)) * width
    val norm = ((v - min) / span).toFloat()
    val y = yOffset + height - norm * height

    drawCircle(
        color = color,
        radius = 10f,
        center = Offset(x, y)
    )
    drawCircle(
        color = Color.White,
        radius = 14f,
        center = Offset(x, y),
        style = Stroke(width = 3f)
    )
}

/**
 * Candlestick price chart. Wick = high–low vertical line, body =
 * open–close rectangle. The whole series is scaled to the min(low)..max(high)
 * range; the newest candle is on the right. Green = close ≥ open, red = close < open.
 */
private fun DrawScope.drawCandles(
    candles: List<Candle>,
    yOffset: Float,
    height: Float,
    width: Float,
    upColor: Color,
    downColor: Color
) {
    if (candles.size < 2) return

    val lo = candles.minOf { it.low }
    val hi = candles.maxOf { it.high }
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0

    // Top/bottom boundary lines — same frame look as the sub-charts.
    drawLine(DiveColors.Border.copy(alpha = 0.3f), Offset(0f, yOffset), Offset(width, yOffset), 1f)
    drawLine(DiveColors.Border.copy(alpha = 0.3f), Offset(0f, yOffset + height), Offset(width, yOffset + height), 1f)

    val n = candles.size
    val slot = width / n
    val bodyW = (slot * 0.62f).coerceAtLeast(1f)
    val wickW = (slot * 0.12f).coerceIn(1f, 3f)

    candles.forEachIndexed { i, c ->
        val cx = (i + 0.5f) * slot
        val color = if (c.close >= c.open) upColor else downColor

        val highY = yOffset + height - (((c.high - lo) / span).toFloat() * height)
        val lowY = yOffset + height - (((c.low - lo) / span).toFloat() * height)
        val openY = yOffset + height - (((c.open - lo) / span).toFloat() * height)
        val closeY = yOffset + height - (((c.close - lo) / span).toFloat() * height)

        // Wick
        drawLine(color = color, start = Offset(cx, highY), end = Offset(cx, lowY), strokeWidth = wickW)

        // Body
        val top = minOf(openY, closeY)
        val bodyH = (maxOf(openY, closeY) - top).coerceAtLeast(1.5f)
        drawRect(color = color, topLeft = Offset(cx - bodyW / 2f, top), size = Size(bodyW, bodyH))
    }
}

/**
 * Heikin-Ashi transform — derived candles that filter out noise and emphasize the trend.
 *   HA_close = (O+H+L+C)/4
 *   HA_open  = (prev HA_open + prev HA_close)/2  (first candle: (O+C)/2)
 *   HA_high  = max(H, HA_open, HA_close);  HA_low = min(L, HA_open, HA_close)
 */
private fun heikinAshi(candles: List<Candle>): List<Candle> {
    if (candles.isEmpty()) return candles
    val out = ArrayList<Candle>(candles.size)
    var prevOpen = (candles[0].open + candles[0].close) / 2.0
    var prevClose = (candles[0].open + candles[0].high + candles[0].low + candles[0].close) / 4.0
    candles.forEachIndexed { i, c ->
        val haClose = (c.open + c.high + c.low + c.close) / 4.0
        val haOpen = if (i == 0) (c.open + c.close) / 2.0 else (prevOpen + prevClose) / 2.0
        val haHigh = maxOf(c.high, haOpen, haClose)
        val haLow = minOf(c.low, haOpen, haClose)
        out.add(c.copy(open = haOpen, high = haHigh, low = haLow, close = haClose))
        prevOpen = haOpen
        prevClose = haClose
    }
    return out
}

/** Marker for the inspect cursor that sits on the candle close (using the low/high scale). */
private fun DrawScope.drawCandleCursorMarker(
    candles: List<Candle>,
    selectedIndex: Int,
    yOffset: Float,
    height: Float,
    width: Float,
    color: Color
) {
    if (candles.size < 2 || selectedIndex >= candles.size) return
    val lo = candles.minOf { it.low }
    val hi = candles.maxOf { it.high }
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val c = candles[selectedIndex]
    val slot = width / candles.size
    val x = (selectedIndex + 0.5f) * slot
    val y = yOffset + height - (((c.close - lo) / span).toFloat() * height)
    drawCircle(color = color, radius = 10f, center = Offset(x, y))
    drawCircle(color = Color.White, radius = 14f, center = Offset(x, y), style = Stroke(width = 3f))
}

private fun getDeltaPct(values: List<Double>, index: Int): Double {
    if (values.size < 2) return 0.0
    val current = values.getOrElse(index) { 0.0 }
    val prevIndex = (index - 1).coerceAtLeast(0)
    val previous = values.getOrElse(prevIndex) { current }
    return if (previous != 0.0) ((current - previous) / previous) * 100.0 else 0.0
}

private fun formatPrice(price: Double): String {
    return if (price >= 1000) {
        "$" + price.format(2, grouped = true)
    } else {
        "$" + price.format(4)
    }
}

@Composable
private fun LoadingCard() {
    DiveCard(title = "LOADING") {
        Text("Fetching 3 series from the Binance public API...", color = DiveColors.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.RedTint15)
            .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.Radius))
            .padding(12.dp),
    ) {
        Text("Error: $msg", color = DiveColors.Red, fontSize = 13.sp)
    }
}

private fun formatBig(v: Double): String = when {
    v >= 1_000_000_000 -> "${'$'}${(v / 1_000_000_000).format(2)}B"
    v >= 1_000_000 -> "${'$'}${(v / 1_000_000).format(2)}M"
    v >= 1_000 -> "${'$'}${(v / 1_000).format(2)}K"
    else -> "${'$'}${v.format(2)}"
}

@Composable
private fun HistoryTableCard(
    state: PositionsUiState,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit
) {
    val prices = state.closePrices
    val oi = state.openInterest
    val global = state.globalRatio
    val acc = state.accountRatio
    val pos = state.positionRatio
    val taker = state.takerRatio
    val funding = state.fundingRate
    val quantBias = state.quantBias

    if (prices.isEmpty()) return

    DiveCard(title = "HISTORICAL OI · L/S DATA TABLE") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Historical Candle Data (${prices.size} Candles)",
                color = DiveColors.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Horizontal Scroll Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .background(DiveColors.BgCardHover)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableHeaderCell("Time", 80.dp)
                        TableHeaderCell("Price", 90.dp)
                        TableHeaderCell("Open Int.", 85.dp)
                        TableHeaderCell("Global L/S%", 80.dp)
                        TableHeaderCell("Account L/S%", 80.dp)
                        TableHeaderCell("Whale L/S%", 80.dp)
                        TableHeaderCell("Taker L/S%", 80.dp)
                        TableHeaderCell("Net Taker V.", 95.dp)
                        TableHeaderCell("Funding", 85.dp)
                        TableHeaderCell("Quant Bias", 85.dp)
                    }

                    // Table rows (Newest to oldest)
                    val size = prices.size
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .height(260.dp)
                            .verticalScroll(scrollState)
                    ) {
                        for (i in (size - 1) downTo 0) {
                            val isSelected = selectedIndex == i
                            val bgColor = if (isSelected) {
                                DiveColors.Accent.copy(alpha = 0.15f)
                            } else {
                                Color.Transparent
                            }
                            
                            val timeStr = oi.getOrNull(i)?.timestamp?.let { formatTime(it, state.period) } ?: ""
                            val priceStr = prices.getOrNull(i)?.let { formatPrice(it) } ?: ""
                            val oiVal = oi.getOrNull(i)?.sumOpenInterestValue ?: 0.0
                            val oiStr = formatBig(oiVal)
                            // The L/S columns now show the LONG/SHORT PERCENTAGE instead of the raw
                            // ratio (1.63) (meaningful to the end user: "62/38" = 62% long / 38% short).
                            val globalLongPct = (global.getOrNull(i)?.longAccount ?: 0.0) * 100.0
                            val accLongPct = (acc.getOrNull(i)?.longAccount ?: 0.0) * 100.0
                            val posLongPct = (pos.getOrNull(i)?.longAccount ?: 0.0) * 100.0

                            val buyVol = taker.getOrNull(i)?.buyVol ?: 0.0
                            val sellVol = taker.getOrNull(i)?.sellVol ?: 0.0
                            val netTakerVal = buyVol - sellVol
                            val netTakerStr = formatBig(netTakerVal)
                            // Taker long% = buy volume / total volume.
                            val takerLongPct = if (buyVol + sellVol > 0.0) buyVol / (buyVol + sellVol) * 100.0 else 0.0
                            
                            val fundingVal = funding.getOrNull(i)?.fundingRate ?: 0.0
                            val fundingStr = "${(fundingVal * 100.0).format(4)}%"
                            val biasVal = quantBias.getOrNull(i) ?: 0.0
                            val biasStr = "${biasVal.format(1, plus = true)}%"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .clickable { 
                                        if (isSelected) onSelectIndex(null) else onSelectIndex(i)
                                    }
                                    .padding(vertical = 8.dp)
                                    .border(
                                        width = if (isSelected) 1.dp else 0.dp,
                                        color = if (isSelected) DiveColors.Accent.copy(alpha = 0.5f) else Color.Transparent
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TableCell(timeStr, 80.dp, DiveColors.TextMuted)
                                TableCell(priceStr, 90.dp, DiveColors.Text)
                                TableCell(oiStr, 85.dp, DiveColors.Cyan)
                                LongShortPctCell(globalLongPct, 80.dp)
                                LongShortPctCell(accLongPct, 80.dp)
                                LongShortPctCell(posLongPct, 80.dp)
                                LongShortPctCell(takerLongPct, 80.dp)
                                TableCell(netTakerStr, 95.dp, Color(0xFFEC4899))
                                TableCell(fundingStr, 85.dp, Color(0xFFFFD600))
                                val biasColor = if (biasVal >= 20.0) DiveColors.Green else if (biasVal <= -20.0) DiveColors.Red else DiveColors.TextDim
                                TableCell(biasStr, 85.dp, biasColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        color = DiveColors.TextDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = DiveFonts.body,
        modifier = Modifier.width(width),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = fontWeight,
        fontFamily = DiveFonts.body,
        modifier = Modifier.width(width),
        textAlign = TextAlign.Center
    )
}

/**
 * Shows the L/S ratio as a **long/short percentage** instead of the raw decimal:
 * green long% / red short% (e.g. "62/38"). longPct 0..100; short = 100-long.
 */
@Composable
private fun LongShortPctCell(longPct: Double, width: Dp) {
    val l = longPct.coerceIn(0.0, 100.0)
    val s = (100.0 - l).coerceIn(0.0, 100.0)
    Row(
        modifier = Modifier.width(width),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = l.format(0),
            color = DiveColors.Green,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body
        )
        Text(
            text = "/",
            color = DiveColors.TextDim,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body
        )
        Text(
            text = s.format(0),
            color = DiveColors.Red,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body
        )
    }
}

private fun formatTime(timestamp: Long, period: String): String {
    val pattern = if (period.endsWith("d")) "dd/MM" else "dd/MM HH:mm"
    return com.diveintocrypto.android.platform.formatTime(timestamp, pattern)
}
