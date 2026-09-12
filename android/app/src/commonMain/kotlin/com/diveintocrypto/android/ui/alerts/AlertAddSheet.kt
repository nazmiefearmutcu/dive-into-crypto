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
import com.diveintocrypto.android.domain.alerts.AlertCondition
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
 * v2 builder: N AND-ed condition rows (kind chips · VERDICT direction chips ·
 * kind-adaptive threshold stepper) joined by "VE" connectors, "+ KOŞUL" adds,
 * cooldown chips (1 DK / 15 DK / 1 SA → [AlertRule.COALESCE_*] windows,
 * TEK ATIŞ → oneShot). EKLE is disabled until symbol + EVERY threshold are
 * valid — nothing is fabricated on the way into the engine's conditions
 * overload [AppContainer.addRule].
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
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

    val tickers by container.liveTickerEngine.tickers.collectAsStateWithLifecycle()

    var symbol by remember { mutableStateOf(presetSymbol.uppercase()) }
    var conditions by remember {
        mutableStateOf(listOf(ConditionDraft(presetKind, AlertRule.DIRECTION_ANY, AlertLabels.defaultThresholdText(presetKind, null))))
    }
    var cooldown by remember { mutableStateOf(AlertLabels.COOLDOWN_CHIPS.first()) }

    val livePrice = tickers[symbol.uppercase()]?.price

    fun reseed(draft: ConditionDraft, kind: AlertKind): ConditionDraft =
        draft.copy(kind = kind, thresholdText = AlertLabels.defaultThresholdText(kind, livePrice))

    val allConditionsValid = conditions.all { draft ->
        val parsed = AlertLabels.parseThreshold(draft.thresholdText)
        !AlertLabels.thresholdRequired(draft.kind) || (parsed != null && parsed > 0.0)
    }
    val canAdd = symbol.isNotBlank() && conditions.isNotEmpty() && allConditionsValid

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
                text = strings.sheetAddRule,
                color = DiveColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontFamily = DiveFonts.body,
            )

            // ── Symbol ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.lblSymbol,
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
                        onValueChange = { symbol = it.uppercase().trim() },
                        singleLine = true,
                        textStyle = TextStyle(color = DiveColors.Text, fontSize = 13.sp, fontFamily = DiveFonts.body),
                        cursorBrush = SolidColor(DiveColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                livePrice?.let {
                    Text(
                        text = "${strings.livePricePrefix} ${'$'}${it.format(ChartMath.priceDecimalsFor(it))}",
                        color = DiveColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            }

            // ── Condition rows (AND-ed, "VE" connectors) ──────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings.lblConditions,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                conditions.forEachIndexed { idx, draft ->
                    if (idx > 0) {
                        Text(
                            text = strings.andConnector,
                            color = DiveColors.TextDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    ConditionRowCard(
                        draft = draft,
                        livePrice = livePrice,
                        canRemove = conditions.size > 1,
                        onKindChange = { kind ->
                            conditions = conditions.mapIndexed { i, d -> if (i == idx) reseed(d, kind) else d }
                        },
                        onDirectionChange = { dir ->
                            conditions = conditions.mapIndexed { i, d -> if (i == idx) d.copy(direction = dir) else d }
                        },
                        onThresholdChange = { text ->
                            conditions = conditions.mapIndexed { i, d -> if (i == idx) d.copy(thresholdText = text) else d }
                        },
                        onStep = { delta ->
                            conditions = conditions.mapIndexed { i, d ->
                                if (i != idx) d else {
                                    val cur = AlertLabels.parseThreshold(d.thresholdText) ?: 0.0
                                    val step = AlertLabels.thresholdStepFor(d.kind, cur)
                                    d.copy(
                                        thresholdText = AlertLabels.formatPrice(
                                            AlertLabels.clampThreshold(d.kind, cur + delta * step),
                                        ),
                                    )
                                }
                            }
                        },
                        onRemove = {
                            conditions = conditions.filterIndexed { i, _ -> i != idx }
                        },
                    )
                }
                // "+ KOŞUL" — adds another AND-ed condition row.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DiveColors.BgCardHover)
                        .border(1.dp, DiveColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .semantics {
                            role = Role.Button
                            contentDescription = strings.a11yAddCondition
                        }
                        .clickable {
                            conditions = conditions +
                                ConditionDraft(AlertKind.PRICE_ABOVE, AlertRule.DIRECTION_ANY, AlertLabels.defaultThresholdText(AlertKind.PRICE_ABOVE, livePrice))
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = strings.btnAddCondition,
                        color = DiveColors.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontFamily = DiveFonts.body,
                    )
                }
            }

            // ── Cooldown chips ────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.lblCooldown,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlertLabels.COOLDOWN_CHIPS.forEach { chip ->
                        KindChip(
                            label = strings.cooldownChipLabel(chip.oneShot, chip.coalesceMs),
                            selected = cooldown == chip,
                            onClick = { cooldown = chip },
                        )
                    }
                }
                Text(
                    text = strings.cooldownNote,
                    color = DiveColors.TextDim,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }

            // ── EKLE ──────────────────────────────────────────────────
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
                        contentDescription = strings.a11yConfirmAddRule
                        stateDescription = if (canAdd) strings.stateAddable else strings.stateDisabled
                    }
                    .clickable(enabled = canAdd) {
                        val conds = conditions.map { d ->
                            val parsed = AlertLabels.parseThreshold(d.thresholdText)
                            AlertCondition(
                                kind = d.kind,
                                direction = if (d.kind == AlertKind.VERDICT) d.direction else AlertRule.DIRECTION_ANY,
                                threshold = if (AlertLabels.thresholdRequired(d.kind)) {
                                    AlertLabels.clampThreshold(d.kind, parsed ?: 0.0)
                                } else 0.0,
                            )
                        }
                        container.addRule(
                            symbol = symbol.trim(),
                            conditions = conds,
                            oneShot = cooldown.oneShot,
                            coalesceMs = if (cooldown.oneShot) AlertRule.COALESCE_DEFAULT_MS else cooldown.coalesceMs,
                        )
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.btnAdd,
                    color = if (canAdd) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else DiveColors.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One editable AND-condition in the v2 builder. */
private data class ConditionDraft(
    val kind: AlertKind,
    val direction: String,
    val thresholdText: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionRowCard(
    draft: ConditionDraft,
    livePrice: Double?,
    canRemove: Boolean,
    onKindChange: (AlertKind) -> Unit,
    onDirectionChange: (String) -> Unit,
    onThresholdChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    val parsedThreshold = AlertLabels.parseThreshold(draft.thresholdText)
    val thresholdOk = !AlertLabels.thresholdRequired(draft.kind) ||
        (parsedThreshold != null && parsedThreshold > 0.0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCardHover)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Kind chips + remove affordance
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    AlertKind.VERDICT,
                    AlertKind.CONFIDENCE_ABOVE,
                    AlertKind.PRICE_ABOVE,
                    AlertKind.PRICE_BELOW,
                    AlertKind.OI_SPIKE_PCT,
                ).forEach { k ->
                    KindChip(
                        label = strings.kindLabel(k.name),
                        selected = draft.kind == k,
                        compact = true,
                        onClick = { onKindChange(k) },
                    )
                }
            }
            if (canRemove) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DiveColors.RedTint15)
                        .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(6.dp))
                        .semantics {
                            role = Role.Button
                            contentDescription = strings.a11yRemoveCondition
                        }
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "×",
                        color = DiveColors.Red,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }

        // Direction chips (VERDICT only)
        if (draft.kind == AlertKind.VERDICT) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    AlertRule.DIRECTION_ANY to strings.dirAny,
                    AlertRule.DIRECTION_LONG to strings.dirLong,
                    AlertRule.DIRECTION_SHORT to strings.dirShort,
                ).forEach { (value, label) ->
                    KindChip(
                        label = label,
                        selected = draft.direction == value,
                        compact = true,
                        onClick = { onDirectionChange(value) },
                    )
                }
            }
        }

        // Threshold (hidden for VERDICT — no threshold semantics)
        if (AlertLabels.thresholdRequired(draft.kind)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton(
                    label = "−",
                    onClick = { onStep(-1) },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DiveDims.Radius))
                        .background(DiveColors.Bg)
                        .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = draft.thresholdText,
                        onValueChange = onThresholdChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (thresholdOk) DiveColors.Text else DiveColors.Red,
                            fontSize = 14.sp,
                            fontFamily = DiveFonts.Mono,
                        ),
                        cursorBrush = SolidColor(DiveColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                StepperButton(
                    label = "+",
                    onClick = { onStep(1) },
                )
            }
            if (!thresholdOk) {
                Text(
                    text = strings.errThresholdPositive,
                    color = DiveColors.Red,
                    fontSize = 10.sp,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val accent = DiveColors.Accent
    val bg = if (selected) accent.copy(alpha = 0.18f) else DiveColors.BgCardHover
    val border = if (selected) accent.copy(alpha = 0.6f) else DiveColors.Border
    val fg = if (selected) accent else DiveColors.TextMuted
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
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = if (compact) 10.sp else 12.sp,
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
