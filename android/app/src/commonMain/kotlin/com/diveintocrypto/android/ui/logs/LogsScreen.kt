package com.diveintocrypto.android.ui.logs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.NetworkLogEntry
import com.diveintocrypto.android.data.binance.NetworkLogKind
import com.diveintocrypto.android.platform.formatTime
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Network Log screen — replaced the paper-trade event log. Shows the HTTP(S) + WSS
 * requests from the NetworkLog singleton, newest on top.
 *
 * Components:
 *   - Top bar: REST + WS counters + Clear button
 *   - Filter row: 2xx / 4xx / 5xx / ERR + search box
 *   - LazyColumn: one row per request (time + method + host + path + status + ms)
 */
@Composable
fun LogsScreen(container: AppContainer) {
    val vm: LogsViewModel = viewModel { LogsViewModel(container) }
    val state by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiveColors.RootBg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderRow(restCount = state.countREST, wsCount = state.countWS, onClear = vm::clearAll)

        FilterBar(
            text = state.filterText,
            onTextChange = vm::setFilterText,
            active = state.statusFilters,
            onToggle = vm::toggleStatusFilter,
        )

        if (state.visible.isEmpty()) {
            EmptyState(hasAny = state.entries.isNotEmpty())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(items = state.visible, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(restCount: Int, wsCount: Int, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountChip("REST", restCount, DiveColors.Cyan)
        Spacer(Modifier.width(8.dp))
        CountChip("WS", wsCount, DiveColors.Purple)
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DiveColors.RedTint15)
                .border(1.dp, DiveColors.RedTint25, RoundedCornerShape(8.dp))
                .clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("Clear", color = DiveColors.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CountChip(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, fontFamily = DiveFonts.body)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), color = DiveColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = DiveFonts.body)
    }
}

@Composable
private fun FilterBar(
    text: String,
    onTextChange: (String) -> Unit,
    active: Set<StatusFilter>,
    onToggle: (StatusFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Search box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DiveDims.Radius))
                .background(DiveColors.BgCard)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) {
                Text("Search host / path / method…", color = DiveColors.TextDim, fontSize = 12.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = DiveColors.Text, fontSize = 13.sp, fontFamily = DiveFonts.body),
                cursorBrush = SolidColor(DiveColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusFilter.values().forEach { f ->
                FilterPill(label = f.label, on = f in active, onClick = { onToggle(f) }, color = colorOf(f))
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, onClick: () -> Unit, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) color.copy(alpha = 0.22f) else DiveColors.BgCard)
            .border(1.dp, if (on) color else DiveColors.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (on) color else DiveColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.body,
        )
    }
}

private fun colorOf(f: StatusFilter): Color = when (f) {
    StatusFilter.SUCCESS -> DiveColors.Green
    StatusFilter.CLIENT_ERROR -> DiveColors.Yellow
    StatusFilter.SERVER_ERROR -> DiveColors.Red
    StatusFilter.NETWORK_ERROR -> DiveColors.Orange
}

@Composable
private fun LogRow(e: NetworkLogEntry) {
    val statusColor = when {
        e.status < 0 -> DiveColors.Orange
        e.status in 200..299 -> DiveColors.Green
        e.status in 400..499 -> DiveColors.Yellow
        e.status in 500..599 -> DiveColors.Red
        else -> DiveColors.TextMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.RadiusSm))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.RadiusSm))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTs(e.startedAtMs),
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (e.kind == NetworkLogKind.WS) DiveColors.Purple.copy(alpha = 0.18f) else DiveColors.Cyan.copy(alpha = 0.18f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    e.method,
                    color = if (e.kind == NetworkLogKind.WS) DiveColors.Purple else DiveColors.Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${if (e.status < 0) "ERR" else e.status}",
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DiveFonts.body,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${e.durationMs}ms",
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${e.host}${e.path}",
            color = DiveColors.Text,
            fontSize = 12.sp,
            fontFamily = DiveFonts.body,
            maxLines = 2,
        )
        if (!e.query.isNullOrEmpty()) {
            Text(
                "?${e.query}",
                color = DiveColors.TextDim,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
                maxLines = 1,
            )
        }
        e.error?.let {
            Text(
                it,
                color = DiveColors.Red,
                fontSize = 10.sp,
                fontFamily = DiveFonts.body,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EmptyState(hasAny: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📡", fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasAny) "No requests match the filter" else "No API requests made yet",
            color = DiveColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!hasAny) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Open the Scanner, Panel, or Positions tab",
                color = DiveColors.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

private fun formatTs(ms: Long): String =
    formatTime(ms, "HH:mm:ss")
