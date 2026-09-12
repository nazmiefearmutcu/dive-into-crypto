package com.diveintocrypto.android.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.platform.formatTime
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * "Alarmlar" screen (route: ALERTS, reached from the More sheet) — v2:
 *
 *   TAB KURALLAR
 *     1. KURAL EKLE button → the shared v2 [AlertAddSheet]
 *     2. Rule cards — symbol · condition summary ("2 koşul · KARAR YÖNÜ(LONG)+
 *        GÜVEN ÜSTÜ · 15 DK") · one-shot/disabled badges · aktif toggle · delete.
 *        v1 rules render as exactly 1 condition (migration is display-only).
 *
 *   TAB DİJEST
 *     Fired history grouped by day, filter chips by symbol AND kind,
 *     tap a row → opens the symbol's panel ([onOpenSymbol]).
 *
 * Everything renders straight from [AppContainer.rules] / [AppContainer.firedHistory]
 * (persisted StateFlows from the alert engine); there is no local copy of the
 * rule state, so toggles/deletes act on the engine's single source of truth.
 */
@Composable
fun AlertsScreen(
    container: AppContainer,
    onOpenSymbol: (String) -> Unit = {},
) {
    val rules by container.rules.collectAsStateWithLifecycle()
    val history by container.firedHistory.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) } // 0 = KURALLAR, 1 = DİJEST
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = strings.alertsTitle,
            lastUpdateMs = null,
            stale = false,
            onRefresh = null,
        )

        // ── Tab switch: KURALLAR | DİJEST ─────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("${strings.tabRules} (${rules.size})", "${strings.tabDigest} (${history.size})").forEachIndexed { idx, label ->
                val selected = tab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) DiveColors.Accent.copy(alpha = 0.18f) else DiveColors.BgCardHover)
                        .border(
                            1.dp,
                            if (selected) DiveColors.Accent.copy(alpha = 0.6f) else DiveColors.Border,
                            RoundedCornerShape(8.dp),
                        )
                        .semantics {
                            role = Role.Tab
                            stateDescription = if (selected) strings.selected else strings.notSelected
                        }
                        .clickable { tab = idx }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) DiveColors.Accent else DiveColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DiveFonts.body,
                    )
                }
            }
        }

        if (tab == 0) {
            RulesTab(
                container = container,
                rules = rules,
                onAdd = { showAddSheet = true },
            )
        } else {
            DigestTab(
                rules = rules,
                history = history,
                onOpenSymbol = onOpenSymbol,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        AlertAddSheet(
            container = container,
            presetSymbol = container.activeSymbol.value,
            presetKind = AlertKind.VERDICT,
            onDismiss = { showAddSheet = false },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// TAB 1 — KURALLAR (v2 rule cards)
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun RulesTab(
    container: AppContainer,
    rules: List<AlertRule>,
    onAdd: () -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Text(
        text = strings.rulesCondNote,
        color = DiveColors.TextDim,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    // ── KURAL EKLE ────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DiveColors.Accent)
            .semantics {
                role = Role.Button
                contentDescription = strings.a11yAddRule
            }
            .clickable(onClick = onAdd)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = strings.btnAddRule,
            // Preset-aware on-accent color — same convention as the other CTAs.
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = DiveFonts.body,
        )
    }

    // ── Rules ─────────────────────────────────────────────────────
    DiveCard(title = strings.cardRules) {
        if (rules.isEmpty()) {
            Text(
                text = strings.noRulesYet,
                color = DiveColors.TextDim,
                fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        onToggle = { container.toggleRule(rule.id) },
                        onDelete = { container.removeRule(rule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: AlertRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .alpha(if (rule.enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.symbol,
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = DiveFonts.body,
                )
                Spacer(Modifier.width(8.dp))
                // v2 condition count badge (v1 rules = 1 — automatic migration display)
                MiniBadge(text = "${rule.effectiveConditions.size} ${strings.badgeConditions}", color = DiveColors.Accent)
            }
            Spacer(Modifier.height(3.dp))
            // v2 condition summary: kinds + cooldown
            Text(
                text = AlertLabels.conditionSummaryLabel(rule),
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = DiveFonts.body,
                maxLines = 2,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AlertLabels.ruleLineLabel(rule),
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = DiveFonts.body,
                )
                if (rule.oneShot) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge(text = strings.badgeOneShot, color = DiveColors.Cyan)
                }
                if (!rule.enabled) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge(text = strings.badgeMuted, color = DiveColors.TextMuted)
                }
            }
        }

        // Aktif/pasif toggle — theme pill language.
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (rule.enabled) DiveColors.Accent else DiveColors.Bg)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
                .semantics {
                    role = Role.Switch
                    stateDescription = if (rule.enabled) strings.active else strings.passive
                }
                .clickable(onClick = onToggle)
                .padding(horizontal = 3.dp),
            contentAlignment = if (rule.enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (rule.enabled) DiveColors.Bg else DiveColors.TextDim),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Delete
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(DiveColors.RedTint15)
                .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(6.dp))
                .semantics {
                    role = Role.Button
                    contentDescription = strings.a11yDeleteRule(rule.symbol)
                }
                .clickable(onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = strings.btnDelete,
                color = DiveColors.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// TAB 2 — DİJEST (fired history grouped by day + symbol/kind filters)
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun DigestTab(
    rules: List<AlertRule>,
    history: List<com.diveintocrypto.android.domain.alerts.FiredAlert>,
    onOpenSymbol: (String) -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    // Filters: symbol + kind ("TÜMÜ" = no filter). Derived from the history itself.
    var symbolFilter by remember { mutableStateOf<String?>(null) }
    var kindFilter by remember { mutableStateOf<AlertKind?>(null) }

    val symbols = history.map { it.symbol }.distinct()
    val kinds = history.map { it.kind }.distinct()

    val filtered = history.filter { fired ->
        (symbolFilter == null || fired.symbol == symbolFilter) &&
            (kindFilter == null || fired.kind == kindFilter)
    }

    DiveCard(title = strings.cardFilters) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(label = strings.chipAllSymbols, selected = symbolFilter == null, onClick = { symbolFilter = null })
                symbols.forEach { sym ->
                    FilterChip(label = sym, selected = symbolFilter == sym, onClick = { symbolFilter = sym })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(label = strings.chipAllKinds, selected = kindFilter == null, onClick = { kindFilter = null })
                kinds.forEach { kind ->
                    FilterChip(
                        label = strings.kindLabel(kind.name),
                        selected = kindFilter == kind,
                        onClick = { kindFilter = kind },
                    )
                }
            }
        }
    }

    DiveCard(title = "${strings.cardTriggers} (${filtered.size}/${history.size})") {
        if (filtered.isEmpty()) {
            Text(
                text = if (history.isEmpty()) strings.noFiredYet
                else strings.noneMatchFilter,
                color = DiveColors.TextDim,
                fontSize = 12.sp,
            )
        } else {
            // Grouped by day (engine already returns newest-first).
            val grouped = filtered.groupBy { formatTime(it.firedTs, "dd.MM.yyyy") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (day, firedList) ->
                    Text(
                        text = day,
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontFamily = DiveFonts.Mono,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        firedList.forEach { fired ->
                            val rule = rules.firstOrNull { it.id == fired.ruleId }
                            HistoryRow(
                                timeLabel = formatTime(fired.firedTs, "HH:mm"),
                                symbol = fired.symbol,
                                lineLabel = AlertLabels.ruleLineLabel(rule),
                                onClick = { onOpenSymbol(fired.symbol) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) DiveColors.Accent.copy(alpha = 0.18f) else DiveColors.BgCardHover
    val border = if (selected) DiveColors.Accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) DiveColors.Accent else DiveColors.TextMuted
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (selected) strings.selected else strings.notSelected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

@Composable
private fun HistoryRow(timeLabel: String, symbol: String, lineLabel: String, onClick: () -> Unit) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(DiveColors.BgCardHover)
            .semantics {
                role = Role.Button
                contentDescription = strings.a11yOpenPanel(symbol)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeLabel,
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.Mono,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = symbol,
            color = DiveColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "· $lineLabel",
            color = DiveColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = DiveFonts.body,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "→",
            color = DiveColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Text(
        text = text,
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
