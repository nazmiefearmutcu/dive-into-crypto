package com.diveintocrypto.android.ui.alerts

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertRule
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.ui.panel.components.ChartMath
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts
import kotlinx.coroutines.launch

/**
 * "KURAL EKLE" bottom sheet — the SINGLE authoring surface for alert rules,
 * shared by the Alarmlar screen and the Scanner's per-row quick-add (🔔).
 *
 * Symbol (prefilled) · kind chips · VERDICT direction chips · threshold
 * text+stepper (kind-adaptive step/clamp via the pure [AlertLabels]) ·
 * one-shot switch. EKLE is disabled until symbol + threshold are valid —
 * nothing is fabricated on the way into [AppContainer.addRule].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlertAddSheet(
    container: AppContainer,
    presetSymbol: String,
    presetKind: AlertKind,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val tickers by container.liveTickerEngine.tickers.collectAsStateWithLifecycle()

    var symbol by remember { mutableStateOf(presetSymbol.uppercase()) }
    var kind by remember { mutableStateOf(presetKind) }
    var direction by remember { mutableStateOf(AlertRule.DIRECTION_ANY) }
    var oneShot by remember { mutableStateOf(false) }
    var thresholdText by remember { mutableStateOf(AlertLabels.defaultThresholdText(presetKind, null)) }

    val livePrice = tickers[symbol.uppercase()]?.price

    val parsedThreshold = AlertLabels.parseThreshold(thresholdText)
    val thresholdOk = !AlertLabels.thresholdRequired(kind) || (parsedThreshold != null && parsedThreshold > 0.0)
    val canAdd = symbol.isNotBlank() && thresholdOk

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
                text = "KURAL EKLE",
                color = DiveColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontFamily = DiveFonts.body,
            )

            // ── Symbol ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "SEMMBOL",
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
                        Text("örn. BTCUSDT", color = DiveColors.TextDim, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase().trim() },
                        singleLine = true,
                        textStyle = TextStyle(color = DiveColors.Text, fontSize = 13.sp, fontFamily = DiveFonts.body),
                        cursorBrush = SolidColor(DiveColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                livePrice?.let {
                    Text(
                        text = "canlı fiyat: ${'$'}${it.format(ChartMath.priceDecimalsFor(it))}",
                        color = DiveColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            }

            // ── Kind chips ────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "TÜR",
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val kinds = listOf(
                        AlertKind.VERDICT,
                        AlertKind.CONFIDENCE_ABOVE,
                        AlertKind.PRICE_ABOVE,
                        AlertKind.PRICE_BELOW,
                        AlertKind.OI_SPIKE_PCT,
                    )
                    kinds.forEach { k ->
                        KindChip(
                            label = AlertLabels.kindLabel(k),
                            selected = kind == k,
                            onClick = {
                                kind = k
                                // Re-seed the threshold when the kind changes.
                                thresholdText = AlertLabels.defaultThresholdText(k, livePrice)
                            },
                        )
                    }
                }
            }

            // ── Direction chips (VERDICT only) ────────────────────────
            if (kind == AlertKind.VERDICT) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "YÖN",
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            AlertRule.DIRECTION_ANY to AlertLabels.directionLabel(AlertRule.DIRECTION_ANY),
                            AlertRule.DIRECTION_LONG to AlertLabels.directionLabel(AlertRule.DIRECTION_LONG),
                            AlertRule.DIRECTION_SHORT to AlertLabels.directionLabel(AlertRule.DIRECTION_SHORT),
                        ).forEach { (value, label) ->
                            KindChip(
                                label = label,
                                selected = direction == value,
                                onClick = { direction = value },
                            )
                        }
                    }
                }
            }

            // ── Threshold (hidden for VERDICT — no threshold semantics) ──
            if (AlertLabels.thresholdRequired(kind)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when (kind) {
                            AlertKind.CONFIDENCE_ABOVE -> "EŞİK (güven %)"
                            AlertKind.OI_SPIKE_PCT -> "EŞİK (OI artış %)"
                            else -> "EŞİK (fiyat)"
                        },
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepperButton(
                            label = "−",
                            onClick = {
                                val cur = parsedThreshold ?: 0.0
                                val step = AlertLabels.thresholdStepFor(kind, cur)
                                thresholdText = AlertLabels.formatPrice(
                                    AlertLabels.clampThreshold(kind, cur - step),
                                )
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(DiveDims.Radius))
                                .background(DiveColors.BgCardHover)
                                .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            BasicTextField(
                                value = thresholdText,
                                onValueChange = { thresholdText = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = if (thresholdOk) DiveColors.Text else DiveColors.Red,
                                    fontSize = 14.sp,
                                    fontFamily = DiveFonts.body,
                                ),
                                cursorBrush = SolidColor(DiveColors.Accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        StepperButton(
                            label = "+",
                            onClick = {
                                val cur = parsedThreshold ?: 0.0
                                val step = AlertLabels.thresholdStepFor(kind, cur)
                                thresholdText = AlertLabels.formatPrice(
                                    AlertLabels.clampThreshold(kind, cur + step),
                                )
                            },
                        )
                    }
                    if (!thresholdOk) {
                        Text(
                            text = "Eşik > 0 olmalı",
                            color = DiveColors.Red,
                            fontSize = 10.sp,
                            fontFamily = DiveFonts.body,
                        )
                    }
                }
            }

            // ── One-shot switch ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { oneShot = !oneShot }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Tek atış",
                        color = DiveColors.Text,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "İlk tetiklemede kural otomatik kapanır",
                        color = DiveColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
                OneShotSwitch(checked = oneShot, onToggle = { oneShot = !oneShot })
            }

            // ── EKLE ──────────────────────────────────────────────────
            val accent = if (canAdd) DiveColors.Accent else DiveColors.TextDim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canAdd) DiveColors.Accent else DiveColors.BgCardHover)
                    .border(
                        1.dp,
                        if (canAdd) DiveColors.Accent else DiveColors.Border,
                        RoundedCornerShape(10.dp),
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Kuralı ekle"
                        stateDescription = if (canAdd) "Eklenebilir" else "Devre dışı"
                    }
                    .clickable(enabled = canAdd) {
                        container.addRule(
                            symbol = symbol.trim(),
                            kind = kind,
                            direction = if (kind == AlertKind.VERDICT) direction else AlertRule.DIRECTION_ANY,
                            threshold = if (AlertLabels.thresholdRequired(kind)) {
                                AlertLabels.clampThreshold(kind, parsedThreshold ?: 0.0)
                            } else 0.0,
                            oneShot = oneShot,
                        )
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "EKLE",
                    // Preset-aware on-accent color — same convention as the other CTAs.
                    color = if (canAdd) MaterialTheme.colorScheme.onPrimary else accent,
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
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = DiveColors.Accent
    val bg = if (selected) accent.copy(alpha = 0.18f) else DiveColors.BgCardHover
    val border = if (selected) accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) accent else DiveColors.TextMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (selected) "Seçili" else "Seçili değil"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(8.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = DiveColors.Accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/** Theme-consistent mini switch (same 44×24 pill language as Settings' ToggleRow). */
@Composable
private fun OneShotSwitch(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) DiveColors.Accent else DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "Açık" else "Kapalı"
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) DiveColors.Bg else DiveColors.TextDim),
        )
    }
}
