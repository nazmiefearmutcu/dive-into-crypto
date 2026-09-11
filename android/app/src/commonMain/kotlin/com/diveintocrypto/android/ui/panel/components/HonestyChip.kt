package com.diveintocrypto.android.ui.panel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
 * Honesty chips — render the ViewModels' staleness fields (isStale / dataAgeMs)
 * without ever fabricating data:
 *   - dataAgeMs == null            → neutral "BAĞLANIYOR" (no WS frame has arrived yet).
 *   - isStale == true              → amber "GECİKME · N sn önce" (last REAL data is aging).
 *   - otherwise (fresh + real age) → nothing is rendered.
 *
 * Compact by design: sits next to the price/header row on Signals / Positions / Panel.
 */
@Composable
fun StaleChip(isStale: Boolean, dataAgeMs: Long?, modifier: Modifier = Modifier) {
    when {
        dataAgeMs == null -> ChipPill(
            text = "BAĞLANIYOR",
            color = DiveColors.TextMuted,
            modifier = modifier,
        )
        isStale -> ChipPill(
            text = "GECİKME · ${staleAgeLabel(dataAgeMs)}",
            color = DiveColors.Warn,
            modifier = modifier,
        )
        // Fresh — no chip (an always-on LIVE badge would be noise).
        else -> Unit
    }
}

@Composable
private fun ChipPill(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/** Pure (unit-testable) human age label: "12 sn önce" / "3 dk önce". null-safe. */
fun staleAgeLabel(dataAgeMs: Long?): String {
    val sec = (dataAgeMs ?: 0L).coerceAtLeast(0L) / 1000
    return if (sec < 60) "$sec sn önce" else "${sec / 60} dk önce"
}
