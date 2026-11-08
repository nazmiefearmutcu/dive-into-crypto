package com.diveintocrypto.android.ui.scanner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAlert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.ShareVerdictPayload
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.formatTime
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.rememberShareVerdict
import com.diveintocrypto.android.ui.alerts.AlertAddSheet
import com.diveintocrypto.android.ui.alerts.AlertLabels
import com.diveintocrypto.android.ui.common.UiLabels
import com.diveintocrypto.android.ui.i18n.DiveStrings
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Scanner — mobile-first redesign.
 *
 * The previous incarnation forced a 16-column cross-ranking table into a
 * 360dp viewport, hidden behind a horizontalScroll that cut off everything
 * past column 5 (Direction/NetNSS/TF/1m). The user could NOT see all the
 * info on a phone like that.
 *
 * New layout, top to bottom:
 *   1. ScanHeroCard       — manual start/stop, scan stats, primary CTA
 *   2. (scanning)         — ProgressBlock with current symbol + bar
 *   3. (after scan)       — TfPillBar (active TF selector, horizontal pills)
 *   4. (after scan)       — CommonInfoChip (cross-TF agreement banner)
 *   5. (after scan)       — Vertical list of CoinResultCards.
 *
 *   Each CoinResultCard packs ALL info for one symbol into a single card,
 *   visible without horizontal scroll:
 *     - rank + symbol + signal pill + live price (row 1)
 *     - confidence bar (0..100%) + risk badge (row 2)
 *     - 4×3 mini-grid showing all 12 timeframes, color-tinted by signal,
 *       label = TF + confidence% (rows 3-5)
 *     - cross-TF agreement chip + "Select" CTA (row 6)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(container: AppContainer, onSelectSymbol: (String) -> Unit = {}) {
    // App-scoped: uses the single instance from AppContainer instead of a screen-scoped
    // viewModel{} so the scan does not stop on navigation between screens or in the background.
    val vm: ScannerViewModel = container.scannerViewModel
    val state by vm.ui.collectAsStateWithLifecycle()
    val s = LocalDiveStrings.current

    // Result lens: "All" (consensus order, first TOP_N) vs "Divergence" (only rows flagged
    // with a whale-L/S bearish divergence, by divergence score). Presents divergence as a
    // discoverable lens without polluting the consensus order (reviewer P0).
    var resultFilter by remember { mutableStateOf(ResultFilter.ALL) }

    // ── Watchlist live prices + favorites (Task 4 — client-side in the Screen).
    // ScannerViewModel exposes no favorites/live-ticker API, so the Screen consumes
    // AppContainer directly: the app-scoped all-market mini-ticker map for the live
    // price overlay, and SettingsStore favorites for the ★ İZLEME lens.
    val liveTickers by container.liveTickerEngine.tickers.collectAsStateWithLifecycle()
    val settings by container.settingsStore.settingsState.collectAsStateWithLifecycle()
    val favoriteSet = remember(settings.favorites) { settings.favorites.map { it.uppercase() }.toSet() }
    var watchOnly by remember { mutableStateOf(false) }
    // Quick-add alert sheet (🔔 on a result row): the symbol it opens for.
    var alertSheetSymbol by remember { mutableStateOf<String?>(null) }
    // PAYLAŞ (v0.3.0): renders the row's verdict as a PNG share card and fires
    // the system share sheet (androidMain util; see platform/Share expect/actual).
    val shareVerdictCard = rememberShareVerdict()
    // ALL = survivors after elimination (as many as the table size); DIVERGENCE = eliminated.
    val eliminatedCount = state.eliminated.size
    // OPT-IN "Divergence Sort": when ON, the SAME survivor pool (state.survivors) is re-sorted
    // by |divergenceScore| desc — the cross-sectional top-K validated by backtest (rows with the
    // highest |divergence|) is surfaced into the visible window. Rows below the threshold
    // (DIVERGENCE_MIN_SHOWN) keep their default (netNss+0.35×div) order and sink to the bottom.
    // When OFF (default), the list stays bit-for-bit identical to today's. This is ONLY a
    // presentation re-sort: the pool/elimination/score are UNCHANGED (sortedByDescending is
    // stable → the relative order of sub-threshold rows is preserved).
    // remember() keyed on the pool + lens: the re-sort runs when its INPUTS change, not on
    // every recomposition (progress ticks used to re-sort the whole list each frame).
    val orderedSurvivors = remember(state.survivors, state.divergenceSort) {
        if (state.divergenceSort) {
            state.survivors.sortedByDescending { row ->
                if (abs(row.divergenceScore) >= ScannerViewModel.DIVERGENCE_MIN_SHOWN)
                    abs(row.divergenceScore) else Double.NEGATIVE_INFINITY
            }
        } else {
            state.survivors
        }
    }
    val lensRows = when (resultFilter) {
        ResultFilter.ALL -> orderedSurvivors.take(state.displaySize)
        ResultFilter.DIVERGENCE -> state.eliminated
    }
    // ★ İZLEME lens: client-side filter of the DISPLAYED list to SettingsStore favorites.
    val displayedRows = if (watchOnly) lensRows.filter { it.symbol.uppercase() in favoriteSet } else lensRows
    val resultSummary = when (resultFilter) {
        ResultFilter.ALL -> s.scanResultSummary(state.universeSize, state.survivors.size, eliminatedCount)
        ResultFilter.DIVERGENCE -> s.scanDivergenceSummary(eliminatedCount)
    }
    // Number of candidate symbols tried but with no whale data available (coverage==0) — added
    // to the empty message to honestly surface silent false-negatives. remember()'d: the
    // recount is O(rows) and only needs to run when the ranking itself changes.
    val uncheckableCount = remember(state.crossRanking) {
        state.crossRanking.count { it.divergenceCoverage == 0 }
    }
    // Dismissal state for the partial-coverage note. Keyed on the failed count so the
    // note re-arms whenever the value changes (new scan resets it 0 → n).
    var failedNoteDismissed by remember(state.failedCount) { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.scanning,
        onRefresh = { vm.startScan() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        // ── 1) Hero / Control card ────────────────────────────────────────
        item("hero") {
            ScanHeroCard(
                state = state,
                resultSummary = resultSummary,
                onStart = vm::startScan,
                onStop = vm::stopDemoScan,
            )
        }

        // ── 1b) Scan controls: table size button + continuous (back-to-back) mode ──
        item("controls") {
            ScanControlsRow(
                displaySize = state.displaySize,
                continuous = state.continuous,
                cycle = state.cycle,
                scanning = state.scanning,
                divergenceSort = state.divergenceSort,
                onSize = vm::setDisplaySize,
                onContinuous = vm::setContinuous,
                onDivergenceSort = vm::setDivergenceSort,
            )
        }

        // ── 1c) Universe depth status (honest, read-only) + rolling ETA ──
        item("universe") {
            UniverseInfoRow(
                universeMode = state.universeMode,
                universeSize = state.universeSize,
                etaSeconds = state.etaSeconds.takeIf { state.scanning },
                onSelectMode = vm::setUniverseMode,
            )
        }

        // ── 2) Live progress while scanning ───────────────────────────────
        if (state.scanning) {
            item("progress") { ScanProgressBlock(state = state) }
        }

        // ── 2b) HONESTY: partial-coverage note — N symbols could NOT be scanned.
        // Dismissible; re-arms whenever the failed count changes (new scan → 0 → n).
        if (state.failedCount > 0 && !failedNoteDismissed) {
            item("failed") {
                FailedScanNote(
                    count = state.failedCount,
                    onDismiss = { failedNoteDismissed = true },
                )
            }
        }

        // Results (survivors after elimination). Also visible during a scan in continuous mode.
        // A completed scan with survivors OR eliminated coins shows the result section. (Previously
        // "all eliminated" fell through to "No Scan Run Yet" and hid the eliminated lens.)
        if (state.survivors.isNotEmpty() || state.eliminated.isNotEmpty()) {
            // ── Result lens toggle ─────────────────────────────────────────
            item("filter") {
                ResultFilterToggle(
                    selected = resultFilter,
                    flaggedCount = eliminatedCount,
                    onSelect = { resultFilter = it },
                )
            }

            // ── ★ İZLEME lens: only SettingsStore favorites (client-side) ──
            item("watch") {
                FilterPill(
                    label = s.scanWatchFilter(favoriteSet.size),
                    selected = watchOnly,
                    accent = DiveColors.Yellow,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { watchOnly = !watchOnly },
                )
            }
            if (watchOnly && displayedRows.isEmpty()) {
                item("nowatch") {
                    NoWatchlistCard(hasFavorites = favoriteSet.isNotEmpty())
                }
            }

            // ALL lens with every candidate eliminated: say so, with the count, instead of an empty list.
            if (resultFilter == ResultFilter.ALL && state.survivors.isEmpty()) {
                item("nosurv") { NoSurvivorsCard(eliminated = eliminatedCount) }
            }

            // ── Divergence lens explanation (only when there are results; one message when empty) ──
            if (resultFilter == ResultFilter.DIVERGENCE && displayedRows.isNotEmpty()) {
                item("divinfo") { DivergenceLensInfo() }
            }

            // Empty eliminated lens: the "no divergence" card only when the eliminated list is really
            // empty. A ★ İZLEME filter that hides everything is explained by NoWatchlistCard above.
            if (resultFilter == ResultFilter.DIVERGENCE && displayedRows.isEmpty() && !watchOnly) {
                item("nodiv") { NoDivergenceCard(uncheckable = uncheckableCount) }
            } else {
                // ── Coin result cards ─────────────────────────────────────
                itemsIndexed(
                    items = displayedRows,
                    key = { _, row -> row.symbol },
                ) { idx, row ->
                    CoinResultCard(
                        row = row,
                        // The scan's own rank (survivor order or pre-elimination position), not the lens index.
                        rank = row.scanRank.takeIf { it > 0 } ?: (idx + 1),
                        allTfs = state.timeframes,
                        livePrice = liveTickers[row.symbol]?.price,
                        onAlert = { alertSheetSymbol = row.symbol },
                        onShare = {
                            val payload = ShareVerdictPayload(
                                symbol = row.symbol,
                                verdict = row.dominantDir.name,
                                confidence = row.perTf.values.maxOfOrNull { it.confidence } ?: 0,
                                perTf = state.timeframes.map { tf ->
                                    tf to (row.perTf[tf]?.signal?.name ?: "N/A")
                                },
                                timestampMs = nowMillis(),
                            )
                            shareVerdictCard(payload)
                        },
                        onSelect = { onSelectSymbol(row.symbol) },
                    )
                }
            }

            // ── Footer with scan time ──────────────────────────────────────
            state.lastScanAtMs?.let {
                item("ts") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(
                            text = s.scanLastScan(formatTs(it)),
                            color = DiveColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // Permanent not-investment-advice line under the signal list (Play policy).
            item("disclaimer") {
                Text(
                    text = s.disclaimerShort,
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                    fontFamily = DiveFonts.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // A scan completed but produced no candidates at all.
        if (state.lastScanAtMs != null && state.survivors.isEmpty() && state.eliminated.isEmpty() && !state.scanning) {
            item("nocand") { NoSurvivorsCard(eliminated = 0) }
        }

        // Initial empty state — only before any scan has run. A FAILED run shows its error card
        // instead; it must never claim "No Scan Run Yet" (PS-13).
        if (state.lastScanAtMs == null && state.error == null && !state.scanning) {
            item("empty") { EmptyStateCard() }
        }

        state.error?.let { err ->
            item("err") {
                if (state.regionBlocked) {
                    // Region block: retrying cannot succeed, so there is no retry button.
                    RegionBlockedCard()
                } else {
                    ScanErrorCard(message = err, onRetry = vm::startScan)
                }
            }
        }

        // Bottom spacer so the last card isn't kissed by the bottom nav bar
        item("spacer") { Spacer(modifier = Modifier.height(16.dp)) }
    }
    }

    // ── Quick-add alert sheet (Task 2c) — the SAME authoring sheet the Alarmlar
    //    screen uses; the row's symbol is prefilled, kind defaults to VERDICT.
    alertSheetSymbol?.let { symbol ->
        AlertAddSheet(
            container = container,
            presetSymbol = symbol,
            presetKind = AlertKind.VERDICT,
            onDismiss = { alertSheetSymbol = null },
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 1) Scan hero card — primary control surface
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun ScanHeroCard(
    state: ScannerUiState,
    resultSummary: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = Brush.linearGradient(
                    // Theme-aware hero gradient: card base → hover elevation (was a pinned
                    // dark hex that looked wrong on the light Daylight/Paper presets).
                    colors = listOf(
                        DiveColors.BgCard,
                        DiveColors.BgCardHover,
                    ),
                ),
            )
            .border(1.dp, DiveColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 3px accent strip (cyan) — keeps the brand element
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiveColors.Cyan),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.scanHeroLabel,
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = heroSubtitle(state, resultSummary, s),
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = s.scanSourceLine(state.timeframes.size),
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Primary CTA — full-width pill button
        PrimaryCtaButton(
            scanning = state.scanning,
            onStart = onStart,
            onStop = onStop,
        )
    }
}

@Composable
private fun PrimaryCtaButton(scanning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val bg = if (scanning) DiveColors.Red else DiveColors.Accent
    val s = LocalDiveStrings.current
    val label = if (scanning) s.scanStopButton else s.scanStartButton
    val icon = if (scanning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow
    // onPrimary follows the preset (dark presets → near-black text on the bright accent,
    // light presets → white on the deep accent) — was pinned Color.White.
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable { if (scanning) onStop() else onStart() }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // container carries the label (a11y: no double-read)
            tint = onAccent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = onAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════
// 2) Progress block while scanning
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun ScanProgressBlock(state: ScannerUiState) {
    val s = LocalDiveStrings.current
    val pct = if (state.totalCount > 0) state.completedCount.toFloat() / state.totalCount else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(DiveColors.Cyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phaseLabel(state.currentPhase, s),
                    color = DiveColors.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    fontFamily = DiveFonts.body,
                )
            }
            Text(
                text = "${state.completedCount}/${state.totalCount}",
                color = DiveColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DiveColors.BgCardHover),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(DiveColors.Cyan, DiveColors.Green),
                        ),
                    ),
            )
        }
        if (state.currentSymbol != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.scanNowLabel,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.currentSymbol,
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

private fun phaseLabel(p: ScanPhase, s: DiveStrings): String = when (p) {
    ScanPhase.UNIVERSE -> s.scanPhaseUniverse
    ScanPhase.PHASE1 -> s.scanPhase1
    ScanPhase.PHASE2 -> s.scanPhase2
    ScanPhase.FINALIZING -> s.scanPhaseRanking
    // HONESTY: IDLE means no scan is running — saying "SCANNING" was a lie.
    ScanPhase.IDLE -> s.scanPhaseIdle
}

// ═════════════════════════════════════════════════════════════════════════
// Coin result card — ONE coin, ALL info, no horizontal scroll
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun CoinResultCard(
    row: CrossRankingRow,
    rank: Int,
    allTfs: List<String>,
    livePrice: Double?,
    onAlert: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
) {
    val isCommonAll = row.countHit == row.totalTfs
    val accentColor = when {
        row.dominantDir.score > 0 -> DiveColors.Green
        row.dominantDir.score < 0 -> DiveColors.Red
        else -> DiveColors.TextMuted
    }
    val cardBg = DiveColors.BgCard
    // Theme-derived celebration borders (were pinned green/yellow hexes that
    // ignored the active preset's up/warn colors).
    val borderColor = when {
        isCommonAll -> DiveColors.Green.copy(alpha = 0.4f)
        row.countHit >= row.totalTfs - 1 -> DiveColors.Yellow.copy(alpha = 0.4f)
        else -> DiveColors.Border
    }
    // Best per-TF confidence — the actual 0-100 number from the consensus engine.
    // Distinct from `row.netNss`, which is the time-weighted cross-rank sort key.
    val bestConfidence = row.perTf.values.maxOfOrNull { it.confidence } ?: 0
    // Canonical mapping lives in the ViewModel (public) — the screen's private
    // duplicate was deleted so there is exactly ONE threshold definition.
    val riskLevel = ScannerViewModel.riskFromAgreement(row.countHit, row.totalTfs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
    ) {
        // 3px top accent strip — color-coded by signal
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(accentColor),
        )

        Column(modifier = Modifier.padding(14.dp)) {
            // ── Row 1: Rank · Symbol · Signal · 🔔 · ⤴ · Price ────────────
            CoinCardHeader(
                row = row,
                rank = rank,
                isCommonAll = isCommonAll,
                accentColor = accentColor,
                livePrice = livePrice,
                onAlert = onAlert,
                onShare = onShare,
            )

            // ── Row 1b: strategy-overlay tags (Regime · MTF gate · Micro) ──
            // ADDITIVE display-only annotations from the scan; tiny pills so the
            // compact card design stays unbloated. Micro renders only when the
            // annotation was actually computed (null = honest silence).
            Spacer(Modifier.height(8.dp))
            OverlayTagRow(row = row)

            Spacer(Modifier.height(12.dp))

            // ── Row 2: CONFIDENCE bar (real per-TF max confidence) + Risk ──────
            ConfidenceBlock(
                confidence = bestConfidence,
                accentColor = accentColor,
                risk = riskLevel,
            )

            Spacer(Modifier.height(8.dp))

            // ── Row 3: SCORE (net_NSS) + agreement count ───────────────────
            ScoreBlock(netNss = row.netNss, countHit = row.countHit, totalTfs = row.totalTfs)

            // ── Row 3b: Whale L/S DIRECTIONAL divergence alert (if detected) ──
            if (abs(row.divergenceScore) >= ScannerViewModel.DIVERGENCE_MIN_SHOWN) {
                Spacer(Modifier.height(8.dp))
                WhaleDivergenceBlock(
                    score = row.divergenceScore,
                    tf = row.divergenceTf,
                    risePct = row.divergenceRisePct,
                    whaleDrop = row.divergenceWhaleDrop,
                    patternDirection = row.divergencePatternDirection,
                )
            }

            // ── Row 3c: VOLATILITY (ATR) block (descriptive — not investment advice) ──
            // Illustrative ATR-scaled levels; does NOT affect score/elimination/ranking.
            row.atrPct?.let { atrPct ->
                if (atrPct > 0.0) {
                    Spacer(Modifier.height(8.dp))
                    RiskAdvisorBlock(atrPct = atrPct)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Rows 4-6: 4×3 TF grid showing ALL 12 timeframes ───────────
            TfGrid(perTf = row.perTf, allTfs = allTfs)

            Spacer(Modifier.height(12.dp))

            // ── Row 7: Select CTA ─────────────────────────────────────────
            CoinCardFooterCta(onSelect = onSelect)
        }
    }
}

/**
 * Tiny strategy-overlay tags for a CoinResultCard, straight from the CrossRankingRow
 * annotation fields (lane A). Regime label, MTF gate ✓/– and — only when actually
 * computed — the microstructure state. Nothing here is invented: no micro annotation
 * → no micro tag.
 */
@Composable
private fun OverlayTagRow(row: CrossRankingRow) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TinyTag(
            text = row.regime,
            color = when (row.regime) {
                "TREND" -> DiveColors.Cyan
                "RANGE" -> DiveColors.Purple
                else -> DiveColors.TextMuted
            },
        )
        TinyTag(
            text = if (row.mtfGate) s.scanMtfPass else s.scanMtfFail,
            color = if (row.mtfGate) DiveColors.Green else DiveColors.TextDim,
        )
        row.microScore?.let {
            val dir = row.microDirection ?: 0
            val arrow = when {
                dir > 0 -> "▲"
                dir < 0 -> "▼"
                else -> "·"
            }
            TinyTag(
                text = s.scanMicroTag(arrow, microShortLabel(row.microLabel, s)),
                color = when {
                    dir > 0 -> DiveColors.Green
                    dir < 0 -> DiveColors.Red
                    else -> DiveColors.TextMuted
                },
            )
        }
    }
}

/** Compact STRONG_BUY→S.BUY style shortening (localized) so the tag stays one-liner on 360dp. */
private fun microShortLabel(label: String?, s: DiveStrings): String = when (label) {
    null -> "—"
    "STRONG_BUY" -> s.microStrongBuy
    "STRONG_SELL" -> s.microStrongSell
    "NEUTRAL" -> s.microNeutral
    else -> label
}

@Composable
private fun TinyTag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = DiveFonts.body,
        letterSpacing = 0.3.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Whale L/S divergence alert block. **The label/color/arrow = DESCRIPTIVE raw whale
 * movement (patternDirection), NOT sign(score).** The score is the EMPIRICAL CONTRARIAN
 * predictive ranking score: a DISTRIBUTION pattern (price ↑ + whale ↓) now ranks
 * UP (+score) because top-trader L/S is contrarian.
 *   • Distribution (patternDirection<0): red ▼ "DISTRIBUTION" — Price +, Whale −.
 *   • Accumulation  (patternDirection≥0): green ▲ "ACCUMULATION" — Price −, Whale +.
 * The score number is ALWAYS shown with its sign; the contrarian note explains why
 * distribution ranks up. The score scales with the TF resolution (1d → high).
 */
@Composable
private fun WhaleDivergenceBlock(
    score: Double,
    tf: String?,
    risePct: Double,
    whaleDrop: Double,
    patternDirection: Int,
) {
    val s = LocalDiveStrings.current
    // Descriptive pattern: did the whale SELL (distribution) or BUY (accumulation)? NOT sign(score).
    val isDistribution = patternDirection < 0
    // Distribution = red ▼ (whales selling), accumulation = green ▲ (whales accumulating).
    val color = if (isDistribution) DiveColors.Red else DiveColors.Green
    val arrow = if (isDistribution) "▼" else "▲"
    val title = if (isDistribution) s.scanDistribution else s.scanAccumulation
    // Distribution: price rose (+), whale fell (−). Accumulation: price fell (−), whale rose (+).
    val priceSign = if (isDistribution) "+" else "−"
    val whaleSign = if (isDistribution) "−" else "+"
    // The ranking score's sign is shown AS IS (distribution = +): a contrarian ranking heuristic.
    val scoreSign = if (score >= 0.0) "+" else "−"
    // The ranking direction (contrarian heuristic) is OPPOSITE the descriptive pattern.
    val contrarianNote = if (isDistribution) s.scanContrarianUp else s.scanContrarianDown
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = color, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = title + (tf?.let { " · $it" } ?: ""),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = s.scanPriceWhaleLine(
                        priceSign = priceSign,
                        risePct = (abs(risePct) * 100).format(1),
                        whaleSign = whaleSign,
                        whaleDrop = (whaleDrop * 100).format(1),
                    ),
                    color = DiveColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = DiveFonts.body,
                    lineHeight = 13.sp,
                )
                Text(
                    text = contrarianNote,
                    color = DiveColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = DiveFonts.body,
                    lineHeight = 13.sp,
                )
            }
        }
        Text(
            text = "$scoreSign${abs(score).format(0)}",
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * VOLATILITY (ATR) block — descriptive only, NOT investment advice.
 *
 * Shows the ATR percentage from the indicator layer (atr_filter → atr_pct) as a volatility
 * yardstick. It carries NO stop, target, position-size, % of account or leverage guidance,
 * and it never touches the score / elimination / ranking.
 */
@Composable
private fun RiskAdvisorBlock(atrPct: Double) {
    val s = LocalDiveStrings.current
    // Theme warning token (per-preset amber) — was a pinned 0xFFF59E0B hex.
    val amber = DiveColors.Warn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.scanVolatilityTitle,
                color = amber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = s.disclaimerShort,
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
            )
        }
        Text(
            text = s.scanVolatilityLine(atrPct.format(2)),
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 13.sp,
        )
        Text(
            text = s.scanVolatilityNote,
            color = DiveColors.TextDim,
            fontSize = 10.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun CoinCardHeader(
    row: CrossRankingRow,
    rank: Int,
    isCommonAll: Boolean,
    accentColor: Color,
    livePrice: Double?,
    onAlert: () -> Unit,
    onShare: () -> Unit,
) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rank chip — always visible so the user can read "#1, #2, …" at a glance.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "#$rank",
                color = DiveColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCommonAll) {
            Text(
                "★",
                color = DiveColors.Green,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = row.symbol,
            color = DiveColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.width(8.dp))
        // Signal pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = row.dominantDir.name,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Quick-add alert affordance (Task 2c) — opens the shared KURAL EKLE sheet.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                .semantics {
                    role = Role.Button
                    contentDescription = s.scanAlertCd(row.symbol)
                }
                .clickable(onClick = onAlert)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AddAlert,
                contentDescription = null, // container carries the a11y label
                tint = DiveColors.Warn,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        // PAYLAŞ — verdict share-card PNG via the system share sheet.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DiveColors.BgCardHover)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(6.dp))
                .semantics {
                    role = Role.Button
                    contentDescription = s.scanShareCd(row.symbol)
                }
                .clickable(onClick = onShare)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = null, // container carries the a11y label
                tint = DiveColors.Accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        LivePriceCell(symbol = row.symbol, rowPrice = row.price, livePrice = livePrice)
    }
}

/**
 * Live-price cell (Task 4) — overlays the watchlist engine's real-time price on
 * the scan-time price and flashes a brief tint on every change (300ms
 * animateColorAsState, subtle by design). No ticker yet → the scan price stays.
 */
@Composable
private fun LivePriceCell(symbol: String, rowPrice: Double, livePrice: Double?) {
    val price = livePrice ?: rowPrice
    var displayed by remember(symbol) { mutableStateOf(price) }
    var flash by remember(symbol) { mutableStateOf(0) } // +1 up · −1 down · 0 none
    LaunchedEffect(price) {
        if (price != displayed) {
            flash = if (price > displayed) 1 else -1
            displayed = price
        }
        if (flash != 0) {
            delay(300)
            flash = 0
        }
    }
    val flashColor by animateColorAsState(
        targetValue = when {
            flash > 0 -> DiveColors.GreenTint25
            flash < 0 -> DiveColors.RedTint25
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "priceFlash",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(flashColor)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            // Magnitude-based decimals: sub-cent contracts (e.g. 1000x-denominated) stay readable.
            text = "\$" + UiLabels.priceLabel(displayed),
            color = DiveColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * TARAMA EVRENİ row. The chips call [ScannerViewModel.setUniverseMode], which persists the mode;
 * it takes effect on the next scan. The mode shown is the one the next scan will use (seeded from
 * the persisted setting), plus the rolling ETA — never a fabricated countdown.
 */
@Composable
private fun UniverseInfoRow(
    universeMode: String,
    universeSize: Int,
    etaSeconds: Long?,
    onSelectMode: (String) -> Unit,
) {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.scanUniverseTitle,
                color = DiveColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = AlertLabels.universeLabel(universeMode, s) +
                    if (universeSize > 0) s.scanUniverseSymbols(universeSize) else "",
                color = DiveColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = s.scanEtaLabel(AlertLabels.etaLabel(etaSeconds, s)),
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = DiveFonts.body,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("TOP20", "TOP50", "TOP100", "TOP250", "ALL").forEach { mode ->
                FilterPill(
                    label = AlertLabels.universeLabel(mode, s),
                    selected = universeMode == mode,
                    accent = DiveColors.Accent,
                    onClick = { if (universeMode != mode) onSelectMode(mode) },
                )
            }
        }
        if (AlertLabels.isFullUniverse(universeMode)) {
            Text(
                text = s.scanUniverseWarning,
                color = DiveColors.Warn,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

/** Honest empty state for the ★ İZLEME lens. */
@Composable
private fun NoWatchlistCard(hasFavorites: Boolean) {
    val s = LocalDiveStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hasFavorites) s.scanWatchNoResults else s.scanWatchEmpty,
            color = DiveColors.TextMuted,
            fontSize = 13.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun ConfidenceBlock(confidence: Int, accentColor: Color, risk: String) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = s.scanConfidence,
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$confidence%",
            color = DiveColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(10.dp))
        // Progress fill
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DiveColors.BgCardHover),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(confidence / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor),
            )
        }
        Spacer(Modifier.width(10.dp))
        RiskChip(risk = risk)
    }
}

/** Localized label for the canonical risk key (LOW / MEDIUM / HIGH / N/A). */
private fun riskText(risk: String, s: DiveStrings): String = when (risk.uppercase()) {
    "LOW" -> s.scanRiskLow
    "MEDIUM" -> s.scanRiskMedium
    "HIGH" -> s.scanRiskHigh
    else -> s.scanRiskNone
}

/**
 * SCORE block — surfaces the time-weighted net NSS that actually drives the
 * cross-rank sort. Previously hidden inside `row.netNss` and miscast as
 * "confidence" → the user could not see WHY one coin outranked another.
 *
 *   `net_nss = Σ final_score (dominant side) − Σ final_score (opposite side)`
 *   `final_score = confidence² × timeWeight / 100`
 *
 * The full integer score is shown alongside its "K-format" abbreviation; both
 * are nice on the eye but the integer is the canonical value.
 */
@Composable
private fun ScoreBlock(netNss: Double, countHit: Int, totalTfs: Int) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = s.scanScore,
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatScore(netNss),
            color = DiveColors.Yellow,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "(${netNss.toInt()})",
            color = DiveColors.TextDim,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(modifier = Modifier.weight(1f))
        // Agreement chip — moved out of the footer so the user can see it
        // alongside the score that depends on it.
        // Theme-derived tints (were pinned green/yellow alpha hexes).
        val (chipBg, chipFg) = when {
            countHit >= 3 -> DiveColors.GreenTint15 to DiveColors.Green
            countHit == 2 -> DiveColors.YellowTint15 to DiveColors.Yellow
            else -> DiveColors.BgCardHover to DiveColors.TextMuted
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(chipBg)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Directional agreement: TFs whose verdict matches the dominant side, out of all TFs.
            Text(
                text = s.scanTfAgree(countHit, totalTfs),
                color = chipFg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun RiskChip(risk: String) {
    val s = LocalDiveStrings.current
    val (bg, fg) = when (risk.uppercase()) {
        "LOW" -> DiveColors.GreenTint15 to DiveColors.Green
        "MEDIUM" -> DiveColors.YellowTint15 to DiveColors.Yellow
        "HIGH" -> DiveColors.RedTint15 to DiveColors.Red
        else -> DiveColors.NeutralTint15 to DiveColors.TextDim
    }
    Text(
        text = riskText(risk, s),
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        fontFamily = DiveFonts.body,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// 4×3 grid of TF cells — ALL 12 timeframes visible, no horizontal scroll.
// Cell = TF label + confidence%, tinted by signal direction.
@Composable
private fun TfGrid(perTf: Map<String, TfSlotState>, allTfs: List<String>) {
    val s = LocalDiveStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = s.scanTimeframesHeader(allTfs.size),
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(8.dp))
        // 3 rows × 4 columns = 12 cells
        val rows = allTfs.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { rowTfs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowTfs.forEach { tf ->
                        TfCell(tf = tf, slot = perTf[tf], modifier = Modifier.weight(1f))
                    }
                    // Fill remaining columns if last row is short
                    repeat(4 - rowTfs.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TfCell(tf: String, slot: TfSlotState?, modifier: Modifier = Modifier) {
    val (bg, fg, borderColor) = when {
        slot == null -> Triple(DiveColors.BgCardHover, DiveColors.TextDim, DiveColors.Border)
        slot.signal.score > 0 -> Triple(DiveColors.GreenTint15, DiveColors.Green, DiveColors.GreenTint25)
        slot.signal.score < 0 -> Triple(DiveColors.RedTint15, DiveColors.Red, DiveColors.RedTint25)
        else -> Triple(DiveColors.NeutralTint15, DiveColors.TextMuted, DiveColors.Border)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tf,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (slot != null) "${slot.confidence}%" else "—",
            color = if (slot != null) fg.copy(alpha = 0.85f) else DiveColors.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * Footer with just the Select CTA — the agreement chip has migrated into the
 * `ScoreBlock` row so that SCORE and `N/12 TF AGREE` sit side-by-side (the user
 * needs both to judge the score's quality).
 */
@Composable
private fun CoinCardFooterCta(onSelect: () -> Unit) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.Accent)
                .clickable(onClick = onSelect)
                .semantics { role = Role.Button }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                text = s.scanSelect,
                // Preset-aware on-accent color — was pinned Color.White.
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Empty state — invites the user to start
// ═════════════════════════════════════════════════════════════════════════
@Composable
private fun EmptyStateCard() {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔎", fontSize = 32.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = s.scanNoRunTitle,
            color = DiveColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.scanNoRunHint,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

/** Empty result with a completed scan: the scan ran, nothing survived / nothing was found. */
@Composable
private fun NoSurvivorsCard(eliminated: Int) {
    val s = LocalDiveStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (eliminated > 0) s.scanNoSurvivors(eliminated) else s.scanNoCandidates,
            color = DiveColors.TextMuted,
            fontSize = 13.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/** Region block (HTTP 451): a dedicated explanation with NO retry control — retrying cannot help. */
@Composable
private fun RegionBlockedCard() {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.Warn.copy(alpha = 0.10f))
            .border(1.dp, DiveColors.Warn.copy(alpha = 0.35f), RoundedCornerShape(DiveDims.Radius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = s.errRegionBlockedTitle,
            color = DiveColors.Warn,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = s.errRegionBlockedBody,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/**
 * Scan error card with an explicit "TEKRAR DENE" retry — wired to the SAME public
 * VM entry the pull-to-refresh gesture uses ([ScannerViewModel.startScan]).
 */
@Composable
private fun ScanErrorCard(message: String, onRetry: () -> Unit) {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.RedTint15)
            .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(DiveDims.Radius))
            .padding(12.dp),
    ) {
        Text(s.scanErrorLine(message), color = DiveColors.Red, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(DiveDims.RadiusSm))
                .background(DiveColors.Red)
                .clickable(onClick = onRetry)
                .semantics { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.btnRetry,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

/** Amber partial-result note: "N sembol taranamadı — kısmi sonuç" with a dismiss affordance. */
@Composable
private fun FailedScanNote(count: Int, onDismiss: () -> Unit) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.Warn.copy(alpha = 0.10f))
            .border(1.dp, DiveColors.Warn.copy(alpha = 0.35f), RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = s.scanFailedNote(count),
            color = DiveColors.Warn,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = DiveFonts.body,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "×",
            color = DiveColors.Warn,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDismiss)
                .semantics {
                    role = Role.Button
                    contentDescription = s.scanFailedNoteDismiss
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────
private fun formatTs(ms: Long): String {
    return formatTime(ms, "dd.MM.yyyy HH:mm:ss")
}

/**
 * Risk mapping note: the screen previously carried a PRIVATE duplicate of the
 * ViewModel's `riskFromAgreement` (and referenced the long-deleted `riskFromCount`).
 * The duplicate is gone — `ScannerViewModel.riskFromAgreement` is the single
 * canonical definition (≥ total-1 → LOW · ≥ total/2 → MEDIUM · > 0 → HIGH · else N/A).
 */

/**
 * Format the cross-rank net NSS for compact display. The raw integer is huge
 * (often 1k-30k after summing final_score across TFs), so we render a K-suffix
 * abbreviation in the SCORE headline and keep the raw int in parentheses so
 * detail-oriented users can still see the canonical value.
 */
private fun formatScore(s: Double): String = when {
    s >= 10000 -> "${(s / 1000).format(1)}K"
    s >= 1000 -> "${(s / 1000).format(2)}K"
    else -> s.toInt().toString()
}

private fun heroSubtitle(state: ScannerUiState, resultSummary: String, s: DiveStrings): String = when (state.currentPhase) {
    ScanPhase.UNIVERSE -> s.scanHeroFetchingUniverse
    ScanPhase.PHASE1 -> s.scanHeroPhase1
    ScanPhase.PHASE2 -> s.scanHeroPhase2
    ScanPhase.FINALIZING -> s.scanHeroRanking
    ScanPhase.IDLE -> when {
        state.universeLoading -> s.scanHeroFetchingUniverse
        state.hotList.isNotEmpty() -> resultSummary // lens-aware (All/Divergence)
        else -> s.scanHeroIdle
    }
}

/** Scanner result lens. */
private enum class ResultFilter { ALL, DIVERGENCE }

/** "All" / "✕ Eliminated (n)" two-pill lens selector. */
@Composable
private fun ResultFilterToggle(
    selected: ResultFilter,
    flaggedCount: Int,
    onSelect: (ResultFilter) -> Unit,
) {
    val s = LocalDiveStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            label = s.scanLensAll,
            selected = selected == ResultFilter.ALL,
            accent = DiveColors.Accent,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ResultFilter.ALL) },
        )
        FilterPill(
            label = s.scanLensEliminated(flaggedCount),
            selected = selected == ResultFilter.DIVERGENCE,
            accent = DiveColors.Warn,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ResultFilter.DIVERGENCE) },
        )
    }
}

/** Scan controls: table size (5/10/15/20/All) + Divergence Sort lens + CONTINUOUS (back-to-back) mode button. */
@Composable
private fun ScanControlsRow(
    displaySize: Int,
    continuous: Boolean,
    cycle: Int,
    scanning: Boolean,
    divergenceSort: Boolean,
    onSize: (Int) -> Unit,
    onContinuous: (Boolean) -> Unit,
    onDivergenceSort: (Boolean) -> Unit,
) {
    val s = LocalDiveStrings.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = s.scanTableSize,
            color = DiveColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = DiveFonts.body,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (n in listOf(5, 10, 15, 20, Int.MAX_VALUE)) {
                FilterPill(
                    label = if (n >= Int.MAX_VALUE / 2) s.scanAllSize else "$n",
                    selected = displaySize == n,
                    accent = DiveColors.Accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onSize(n) },
                )
            }
        }
        // ── OPT-IN "Divergence Sort" lens (default OFF) ─────────────
        // When ON, the SAME survivors are shown by |divergence score| desc (the cross-sectional
        // top-K validated by backtest). Only the display order changes; score/elimination are unchanged.
        FilterPill(
            label = if (divergenceSort) s.scanDivSortOn else s.scanDivSortOff,
            selected = divergenceSort,
            accent = DiveColors.Warn,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDivergenceSort(!divergenceSort) },
        )
        if (divergenceSort) {
            Text(
                text = s.scanDivSortNote,
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = DiveFonts.body,
            )
        }
        FilterPill(
            label = if (continuous) {
                if (scanning) s.scanContinuousCycle(cycle) else s.scanContinuousOn
            } else s.scanContinuousOff,
            selected = continuous,
            accent = DiveColors.Cyan,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onContinuous(!continuous) },
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent.copy(alpha = 0.18f) else DiveColors.BgCard
    val border = if (selected) accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) accent else DiveColors.TextMuted
    val s = LocalDiveStrings.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            // A11y: exposed as a button whose selection state is announced.
            .semantics {
                role = Role.Button
                stateDescription = if (selected) s.scanSelected else s.scanNotSelected
            }
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

/** Info card that explains the "Divergence" lens concept once (discoverability). */
@Composable
private fun DivergenceLensInfo() {
    val s = LocalDiveStrings.current
    // Theme warning token — was a pinned 0xFFF59E0B amber that clashed with light presets.
    val amber = DiveColors.Warn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(amber.copy(alpha = 0.08f))
            .border(1.dp, amber.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = s.scanDivInfoTitle,
            color = amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = s.scanDivInfoBody,
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 15.sp,
        )
        // ── Research note: descriptive only; no hit rates are shown (none is validated in-app). ──
        // It does NOT affect signal/ranking/elimination logic.
        Text(
            text = s.scanResearchNoteTitle,
            color = amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = s.scanResearchNoteBody,
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            lineHeight = 15.sp,
        )
    }
}

/** Info shown when the "Divergence" lens is empty. If `uncheckable` > 0, honestly notes
 *  the number of symbols whose whale data could not be fetched (silent false-negative risk). */
@Composable
private fun NoDivergenceCard(uncheckable: Int) {
    val s = LocalDiveStrings.current
    val msg = s.scanNoDivergence(uncheckable)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = msg,
            color = DiveColors.TextMuted,
            fontSize = 13.sp,
            fontFamily = DiveFonts.body,
        )
    }
}
