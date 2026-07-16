package com.diveintocrypto.android.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.ui.nav.NavRoute
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts
import com.diveintocrypto.android.util.Translator
import kotlinx.coroutines.launch

/**
 * Compact mobile top bar — ~56dp. Shows brand + current page label.
 * The earlier paper-bot pulse + refresh chip were dropped along with the
 * paper subsystem.
 */
@Composable
fun MobileTopBar(
    pageTitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(0.dp))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DIVE",
            color = DiveColors.Accent,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            letterSpacing = 1.5.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = ".",
            color = DiveColors.Green,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            fontFamily = DiveFonts.body,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Box(modifier = Modifier.height(20.dp).width(1.dp).background(DiveColors.Border))
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = pageTitle.uppercase(),
            color = DiveColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 5-cell bottom navigation: 4 primary destinations + "More" overflow sheet
 * for Performance, Logs, Settings.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MobileBottomBar(
    currentRoute: NavRoute,
    lang: String = "en",
    onNavigate: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOverflow by remember { mutableStateOf(false) }
    val overflowSheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(0.dp))
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavRoute.BottomBarRoutes.forEach { route ->
                BottomNavCell(
                    label = Translator.tr(route.label, lang),
                    icon = if (route == currentRoute) route.iconFilled else route.iconOutlined,
                    active = route == currentRoute,
                    onClick = { onNavigate(route) },
                )
            }
            val overflowActive = !currentRoute.inBottomBar
            BottomNavCell(
                label = Translator.tr("More", lang),
                icon = Icons.Rounded.Apps,
                active = overflowActive,
                onClick = { showOverflow = true },
            )
        }
    }

    if (showOverflow) {
        ModalBottomSheet(
            onDismissRequest = { showOverflow = false },
            sheetState = overflowSheetState,
            containerColor = DiveColors.BgCard,
            scrimColor = Color(0xAA000000),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = Translator.tr("MORE", lang),
                    color = DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                NavRoute.OverflowRoutes.forEach { route ->
                    OverflowRow(
                        route = route,
                        label = Translator.tr(route.label, lang),
                        active = route == currentRoute,
                        onClick = {
                            scope.launch { overflowSheetState.hide() }.invokeOnCompletion {
                                showOverflow = false
                                onNavigate(route)
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BottomNavCell(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) DiveColors.Accent else DiveColors.TextMuted
    val labelColor = if (active) DiveColors.Text else DiveColors.TextMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(DiveDims.Radius))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) Color(0x265B8DEF) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun OverflowRow(route: NavRoute, label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Color(0x265B8DEF) else DiveColors.BgCardHover
    val fg = if (active) DiveColors.Accent else DiveColors.Text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(bg)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (active) route.iconFilled else route.iconOutlined,
            contentDescription = route.label,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
