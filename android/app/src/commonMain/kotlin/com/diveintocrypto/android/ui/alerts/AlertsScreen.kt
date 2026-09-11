package com.diveintocrypto.android.ui.alerts

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
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.platform.formatTime
import com.diveintocrypto.android.ui.panel.components.DiveCard
import com.diveintocrypto.android.ui.panel.components.PageHeader
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * "Alarmlar" screen (route: ALARMLAR, reached from the More sheet):
 *   1. KURAL EKLE button → the shared [AlertAddSheet]
 *   2. Rules list — symbol · TR kind label · threshold · one-shot/disabled
 *      badges · aktif toggle · delete
 *   3. Fired history (newest first) — "14:32 BTCUSDT · LONG kararı · güven 87"
 *
 * Everything renders straight from [AppContainer.rules] / [AppContainer.firedHistory]
 * (persisted StateFlows from the alert engine); there is no local copy of the
 * rule state, so toggles/deletes act on the engine's single source of truth.
 */
@Composable
fun AlertsScreen(container: AppContainer) {
    val rules by container.rules.collectAsStateWithLifecycle()
    val history by container.firedHistory.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = "Alarmlar",
            lastUpdateMs = null,
            stale = false,
            onRefresh = null,
        )

        Text(
            text = "KARAR / GÜVEN / OI kuralları tarama döngüsünde, FİYAT kuralları " +
                "canlı mini-ticker akışında değerlendirilir. Aynı kural 1 dk içinde " +
                "en fazla bir kez tetiklenir.",
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
                    contentDescription = "Yeni alarm kuralı ekle"
                }
                .clickable { showAddSheet = true }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+ KURAL EKLE",
                // Preset-aware on-accent color — same convention as the other CTAs.
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = DiveFonts.body,
            )
        }

        // ── Rules ─────────────────────────────────────────────────────
        DiveCard(title = "KURALLAR (${rules.size})") {
            if (rules.isEmpty()) {
                Text(
                    text = "Henüz kural yok — KURAL EKLE ile oluştur.",
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

        // ── Fired history (newest first — engine guarantees the order) ──
        DiveCard(title = "TETİKLENMELER (${history.size})") {
            if (history.isEmpty()) {
                Text(
                    text = "Henüz alarm tetiklenmedi.",
                    color = DiveColors.TextDim,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    history.forEach { fired ->
                        val rule = rules.firstOrNull { it.id == fired.ruleId }
                        HistoryRow(timeLabel = formatTime(fired.firedTs, "HH:mm"), symbol = fired.symbol, lineLabel = AlertLabels.ruleLineLabel(rule))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        AlertAddSheet(
            container = container,
            presetSymbol = container.activeSymbol.value,
            presetKind = com.diveintocrypto.android.domain.alerts.AlertKind.VERDICT,
            onDismiss = { showAddSheet = false },
        )
    }
}

@Composable
private fun RuleRow(
    rule: AlertRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
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
                Text(
                    text = AlertLabels.kindLabel(rule.kind),
                    color = DiveColors.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    fontFamily = DiveFonts.body,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AlertLabels.ruleLineLabel(rule),
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = DiveFonts.body,
                )
                if (rule.oneShot) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge(text = "TEK ATIŞ", color = DiveColors.Cyan)
                }
                if (!rule.enabled) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge(text = "SESSİZ", color = DiveColors.TextMuted)
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
                    stateDescription = if (rule.enabled) "Aktif" else "Pasif"
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
                    contentDescription = "${rule.symbol} kuralını sil"
                }
                .clickable(onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = "SİL",
                color = DiveColors.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = DiveFonts.body,
            )
        }
    }
}

@Composable
private fun HistoryRow(timeLabel: String, symbol: String, lineLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(DiveColors.BgCardHover)
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
