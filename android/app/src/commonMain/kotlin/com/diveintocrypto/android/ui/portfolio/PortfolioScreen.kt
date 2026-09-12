package com.diveintocrypto.android.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.portfolio.PositionPnl
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.common.UiLabels
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts
import kotlinx.coroutines.launch

/**
 * PORTFÖY — local-only position tracker (More sheet → "Portföy").
 *
 *   - rows: symbol · direction chip · entry · size · LIVE mark price +
 *     unrealized P&L% (colored) + P&L nominal + engine-agreement tag
 *     (UYUMLU green / KARŞIT red / BELİRSİZ gray) — all recomputed by the
 *     container's [AppContainer.portfolioPnl] flow on every ticker tick
 *   - add/edit sheet: symbol / entry price / size / direction chips
 *     (edit: symbol is immutable in the store — the sheet says so)
 *   - delete with a confirm dialog (no accidental wipes)
 *   - totals footer: Σ nominal P&L over COMPLETE rows + count of excluded
 *     incomplete ones (no mark price yet — never zero-filled)
 *   - honesty caption: "giriş fiyatı cihazında saklanır · borsa bağlantısı yok"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(container: AppContainer) {
    val pnl by container.portfolioPnl.collectAsStateWithLifecycle()
    var sheetEntry by remember { mutableStateOf<PositionPnl?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PositionPnl?>(null) }
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

    // Live marks come from the app-scoped mini-ticker engine — make sure it runs.
    LaunchedEffect(Unit) { container.liveTickerEngine.ensureStarted() }

    // ── Totals across COMPLETE rows only; incomplete counted, never zero-filled ──
    val complete = pnl.filter { it.unrealizedPnl != null }
    val incompleteCount = pnl.size - complete.size
    val totalPnl = complete.sumOf { it.unrealizedPnl ?: 0.0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = strings.portfolioTitle,
            lastUpdateMs = null,
            stale = false,
            onRefresh = null,
        )

        Text(
            text = strings.portfolioLocalOnly,
            color = DiveColors.TextDim,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
        )

        if (pnl.isEmpty()) {
            // Honest empty state with a direct CTA.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DiveColors.BgCard)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = strings.noPositions,
                    color = DiveColors.TextMuted,
                    fontSize = 13.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.noPositionsHint,
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(14.dp))
                AccentCta(label = strings.btnAddPosition, onClick = { showAddSheet = true })
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DiveColors.Accent)
                    .semantics {
                        role = Role.Button
                        contentDescription = strings.a11yAddPosition
                    }
                    .clickable { showAddSheet = true }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+ ${strings.btnAddPosition}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = DiveFonts.body,
                )
            }

            DiveCard(title = "${strings.cardPositions} (${pnl.size})") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pnl.forEach { row ->
                        PortfolioRow(
                            row = row,
                            onEdit = { sheetEntry = row },
                            onDelete = { deleteTarget = row },
                        )
                    }
                }
            }

            // ── Totals footer ─────────────────────────────────────────────
            val totalToken = UiLabels.pnlColorToken(totalPnl)
            DiveCard(title = strings.cardTotals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Σ P&L",
                        color = DiveColors.TextMuted,
                        fontSize = 12.sp,
                        fontFamily = DiveFonts.body,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = UiLabels.pnlNominalLabel(totalPnl),
                        color = tokenColor(totalToken),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = DiveFonts.Mono,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = strings.totalsLine(complete.size, incompleteCount),
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = DiveFonts.body,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        PortfolioEntrySheet(
            container = container,
            existing = null,
            onDismiss = { showAddSheet = false },
        )
    }
    sheetEntry?.let { target ->
        PortfolioEntrySheet(
            container = container,
            existing = target,
            onDismiss = { sheetEntry = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = DiveColors.BgCard,
            titleContentColor = DiveColors.Text,
            textContentColor = DiveColors.TextMuted,
            title = { Text(strings.dlgDeletePositionTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    strings.deletePositionBody(target.entry.symbol),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.removePortfolioEntry(target.entry.id)
                    deleteTarget = null
                }) {
                    Text(strings.btnDelete, color = DiveColors.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(strings.btnCancel, color = DiveColors.TextMuted)
                }
            },
        )
    }
}

@Composable
private fun PortfolioRow(
    row: PositionPnl,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    val entry = row.entry
    val pctToken = UiLabels.pnlColorToken(row.unrealizedPct)
    val agreeToken = UiLabels.agreementColorToken(row.engineAgreement)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .clickable(onClick = onEdit)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        // Row 1: symbol · direction chip · mark price · P&L%
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.symbol,
                color = DiveColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(6.dp))
            DirectionChip(direction = entry.direction)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = row.markPrice?.let { "${'$'}${it.format(ChartDecimals.forPrice(it))}" } ?: "—",
                color = if (row.markPrice != null) DiveColors.Text else DiveColors.TextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = DiveFonts.Mono,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = UiLabels.pnlPercentLabel(row.unrealizedPct),
                color = tokenColor(pctToken),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.Mono,
            )
        }
        Spacer(Modifier.height(3.dp))
        // Row 2: entry · size · nominal P&L · agreement tag · delete
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.entrySizeLine(
                    entry = "${'$'}${entry.entryPrice.format(ChartDecimals.forPrice(entry.entryPrice))}",
                    size = entry.size.format(SizeDecimals.forSize(entry.size)),
                ),
                color = DiveColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = UiLabels.pnlNominalLabel(row.unrealizedPnl),
                color = tokenColor(pctToken),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.Mono,
            )
            Spacer(Modifier.width(8.dp))
            AgreementTag(
                label = when (row.engineAgreement) {
                    "AGREE" -> strings.agreeTag
                    "AGAINST" -> strings.againstTag
                    else -> strings.unclearTag
                },
                token = agreeToken,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DiveColors.RedTint15)
                    .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(6.dp))
                    .semantics {
                        role = Role.Button
                        contentDescription = "${entry.symbol} ${strings.a11yDeletePosition}"
                    }
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = strings.btnDelete,
                    color = DiveColors.Red,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

@Composable
private fun DirectionChip(direction: String) {
    val isLong = direction.equals("LONG", ignoreCase = true)
    val isShort = direction.equals("SHORT", ignoreCase = true)
    val color = when {
        isLong -> DiveColors.Green
        isShort -> DiveColors.Red
        else -> DiveColors.TextMuted
    }
    val label = when {
        isLong -> "LONG"
        isShort -> "SHORT"
        else -> direction.uppercase()
    }
    Text(
        text = label,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        fontFamily = DiveFonts.body,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun AgreementTag(label: String, token: String) {
    val color = tokenColor(token)
    Text(
        text = label,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        fontFamily = DiveFonts.body,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Theme-token mapping for the pure UiLabels color tokens (preset-aware). */
@Composable
private fun tokenColor(token: String): Color = when (token) {
    UiLabels.TOK_GREEN -> DiveColors.Green
    UiLabels.TOK_RED -> DiveColors.Red
    UiLabels.TOK_WARN -> DiveColors.Warn
    else -> DiveColors.TextMuted
}

@Composable
private fun AccentCta(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DiveColors.Accent)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/** Pure decimal-choice helpers (kept tiny; the big formatters live in UiLabels). */
private object ChartDecimals {
    fun forPrice(price: Double): Int = when {
        price >= 1000.0 -> 2
        price >= 1.0 -> 3
        else -> 5
    }
}

private object SizeDecimals {
    fun forSize(size: Double): Int = if (size >= 100.0) 2 else 4
}

// ═══════════════════════════════════════════════════════════════════════
// Add / edit sheet — symbol · entry price · size · direction chips.
// Edit mode: symbol is IMMUTABLE in the store (no update path) — said so.
// ═══════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PortfolioEntrySheet(
    container: AppContainer,
    existing: PositionPnl?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tickers by container.liveTickerEngine.tickers.collectAsStateWithLifecycle()
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

    val isEdit = existing != null
    var symbol by remember { mutableStateOf(existing?.entry?.symbol?.uppercase() ?: container.activeSymbol.value.uppercase()) }
    var direction by remember {
        mutableStateOf(existing?.entry?.direction ?: com.diveintocrypto.android.domain.portfolio.PortfolioEntry.DIRECTION_LONG)
    }
    var entryPriceText by remember { mutableStateOf(existing?.entry?.entryPrice?.format(ChartDecimals.forPrice(existing.entry.entryPrice)) ?: "") }
    var sizeText by remember { mutableStateOf(existing?.entry?.size?.format(SizeDecimals.forSize(existing.entry.size)) ?: "") }

    val livePrice = tickers[symbol.uppercase()]?.price
    val parsedEntry = AlertNum.parsePrice(entryPriceText)
    val parsedSize = AlertNum.parsePrice(sizeText)
    val symbolOk = symbol.isNotBlank()
    val entryOk = parsedEntry != null && parsedEntry > 0.0
    val sizeOk = parsedSize != null && parsedSize > 0.0
    val canSave = symbolOk && entryOk && sizeOk

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DiveColors.BgCard,
        scrimColor = DiveColors.Scrim,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiveColors.Border),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEdit) strings.sheetEditPosition else strings.btnAddPosition,
                color = DiveColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontFamily = DiveFonts.body,
            )

            // ── Symbol ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isEdit) strings.lblSymbolImmutable else strings.lblSymbol,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DiveDims.Radius))
                        .background(DiveColors.BgCardHover)
                        .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (symbol.isEmpty()) {
                        Text(strings.symbolHint, color = DiveColors.TextDim, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = symbol,
                        onValueChange = { if (!isEdit) symbol = it.uppercase().trim() },
                        singleLine = true,
                        readOnly = isEdit,
                        textStyle = TextStyle(
                            color = if (isEdit) DiveColors.TextMuted else DiveColors.Text,
                            fontSize = 13.sp,
                            fontFamily = DiveFonts.body,
                        ),
                        cursorBrush = SolidColor(DiveColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                livePrice?.let {
                    Text(
                        text = "${strings.livePricePrefix} ${'$'}${it.format(ChartDecimals.forPrice(it))}",
                        color = DiveColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            }

            // ── Direction chips ───────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.lblDirection,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.diveintocrypto.android.domain.portfolio.PortfolioEntry.DIRECTION_LONG,
                        com.diveintocrypto.android.domain.portfolio.PortfolioEntry.DIRECTION_SHORT,
                    ).forEach { dir ->
                        val selected = direction.equals(dir, ignoreCase = true)
                        val accent = if (dir == com.diveintocrypto.android.domain.portfolio.PortfolioEntry.DIRECTION_LONG) DiveColors.Green else DiveColors.Red
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) accent.copy(alpha = 0.18f) else DiveColors.BgCardHover)
                                .border(
                                    1.dp,
                                    if (selected) accent.copy(alpha = 0.6f) else DiveColors.Border,
                                    RoundedCornerShape(8.dp),
                                )
                                .semantics {
                                    role = Role.Button
                                    stateDescription = if (selected) strings.selected else strings.notSelected
                                }
                                .clickable { direction = dir }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = dir,
                                color = if (selected) accent else DiveColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = DiveFonts.body,
                            )
                        }
                    }
                }
            }

            // ── Entry price ───────────────────────────────────────────
            NumberFieldRow(
                label = strings.lblEntryPrice,
                value = entryPriceText,
                onValueChange = { entryPriceText = it },
                valid = entryOk,
                hint = if (entryOk) null else strings.hintPositiveNumber,
                trailing = livePrice?.let {
                    {
                        Text(
                            text = "${strings.btnFillLive} ${'$'}${it.format(ChartDecimals.forPrice(it))}",
                            color = DiveColors.Accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DiveFonts.body,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { entryPriceText = it.format(ChartDecimals.forPrice(it)) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                },
            )

            // ── Size ──────────────────────────────────────────────────
            NumberFieldRow(
                label = strings.lblSize,
                value = sizeText,
                onValueChange = { sizeText = it },
                valid = sizeOk,
                hint = if (sizeOk) null else strings.hintPositiveSize,
                trailing = null,
            )

            // ── SAVE ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canSave) DiveColors.Accent else DiveColors.BgCardHover)
                    .border(
                        1.dp,
                        if (canSave) DiveColors.Accent else DiveColors.Border,
                        RoundedCornerShape(10.dp),
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isEdit) strings.a11yUpdatePosition else strings.a11yAddPositionConfirm
                        stateDescription = if (canSave) strings.stateAddable else strings.stateDisabled
                    }
                    .clickable(enabled = canSave) {
                        if (isEdit) {
                            container.updatePortfolioEntry(
                                id = existing!!.entry.id,
                                entryPrice = parsedEntry,
                                size = parsedSize,
                                direction = direction.uppercase(),
                            )
                        } else {
                            container.addPortfolioEntry(
                                symbol = symbol.trim(),
                                entryPrice = parsedEntry ?: 0.0,
                                size = parsedSize ?: 0.0,
                                direction = direction.uppercase(),
                            )
                        }
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isEdit) strings.btnUpdate else strings.btnAdd,
                    color = if (canSave) MaterialTheme.colorScheme.onPrimary else DiveColors.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumberFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    valid: Boolean,
    hint: String?,
    trailing: (@Composable () -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = DiveColors.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DiveDims.Radius))
                    .background(DiveColors.BgCardHover)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text("0.00", color = DiveColors.TextDim, fontSize = 13.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (valid || value.isEmpty()) DiveColors.Text else DiveColors.Red,
                        fontSize = 13.sp,
                        fontFamily = DiveFonts.Mono,
                    ),
                    cursorBrush = SolidColor(DiveColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
        if (hint != null && !valid) {
            Text(text = hint, color = DiveColors.Red, fontSize = 10.sp, fontFamily = DiveFonts.body)
        }
    }
}

/** Shared number parsing for the sheet (same rules as the alert thresholds). */
private object AlertNum {
    fun parsePrice(raw: String): Double? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return v
    }
}
