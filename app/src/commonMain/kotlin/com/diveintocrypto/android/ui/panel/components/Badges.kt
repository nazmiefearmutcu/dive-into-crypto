package com.diveintocrypto.android.ui.panel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * .signal-badge — translation of style.css:173-189.
 *   padding: 3px 10px; border-radius: 4px; font-size: 12px; font-weight: 700;
 *   font-family: var(--font); letter-spacing: 0.3px;
 *
 *   .signal-strong_buy, .signal-buy   { background: rgba(34,197,94,0.15);  color: var(--green); }
 *   .signal-strong_sell, .signal-sell { background: rgba(239,68,68,0.15);  color: var(--red); }
 *   .signal-neutral                   { background: rgba(139,143,163,0.15);color: var(--text-muted); }
 */
@Composable
fun SignalBadge(signal: String, modifier: Modifier = Modifier) {
    val s = signal.uppercase()
    val (bg, fg) = when (s) {
        "STRONG_BUY", "BUY" -> DiveColors.GreenTint15 to DiveColors.Green
        "STRONG_SELL", "SELL" -> DiveColors.RedTint15 to DiveColors.Red
        else -> DiveColors.NeutralTint15 to DiveColors.TextMuted
    }
    Text(
        text = s,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        fontFamily = DiveFonts.body,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * .risk-badge — same shape as signal-badge per style.css:173-189.
 *   .risk-low    { rgba(34,197,94,0.15)  + green }
 *   .risk-medium { rgba(234,179,8,0.15)  + yellow }
 *   .risk-high   { rgba(239,68,68,0.15)  + red }
 *   .risk-n/a    { rgba(139,143,163,0.1) + text-dim }
 */
@Composable
fun RiskBadge(risk: String, modifier: Modifier = Modifier) {
    val r = risk.uppercase()
    val (bg, fg) = when (r) {
        "LOW" -> DiveColors.GreenTint15 to DiveColors.Green
        "MEDIUM" -> DiveColors.YellowTint15 to DiveColors.Yellow
        "HIGH" -> DiveColors.RedTint15 to DiveColors.Red
        else -> DiveColors.NeutralTint15 to DiveColors.TextDim
    }
    Text(
        text = r,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        fontFamily = DiveFonts.body,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
