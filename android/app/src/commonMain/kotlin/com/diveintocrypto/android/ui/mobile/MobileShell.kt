package com.diveintocrypto.android.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.ui.alerts.AlertBannerHost
import com.diveintocrypto.android.ui.alerts.AlertsScreen
import com.diveintocrypto.android.ui.logs.LogsScreen
import com.diveintocrypto.android.ui.nav.NavRoute
import com.diveintocrypto.android.ui.panel.PanelScreen
import com.diveintocrypto.android.ui.performance.PerformanceScreen
import com.diveintocrypto.android.ui.positions.PositionsScreen
import com.diveintocrypto.android.ui.portfolio.PortfolioScreen
import com.diveintocrypto.android.ui.settings.SettingsScreen
import com.diveintocrypto.android.ui.settings.AppearanceScreen
import com.diveintocrypto.android.ui.signals.SignalsScreen
import com.diveintocrypto.android.ui.scanner.ScannerScreen
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * Mobile shell — 9-screen scanner + market-data app after paper-mode
 * removal. Each non-scanner screen consumes Binance public APIs:
 *   - Panel       → live klines + 12-TF consensus + candlestick chart
 *   - Scanner     → multi-TF cross-rank scanner (existing)
 *   - Positions   → Open Interest + Top Long/Short Ratio
 *   - Signals     → indicator detail table for active symbol
 *   - Alarmlar    → alert rules v2 + digest (More sheet)
 *   - Portföy     → local-only position tracker (More sheet)
 *   - Performance → 24h gainers/losers leaderboard + engine evidence
 *   - Logs        → live HTTP activity log
 *   - Settings    → theme + notifications + background scans + about
 *
 * LAYOUTS:
 *   - phone (<840dp width): single-pane NavHost.
 *   - tablet (≥840dp): list-detail two-pane — left = scanner list, right =
 *     active-symbol detail (Panel · OI·L/S · Signals tabs). Selection is
 *     driven by the shared [AppContainer.activeSymbol]; the bottom bar stays.
 *
 * A live PULSE STRIP (price · 24h% · funding countdown for the active symbol)
 * sits under the top bar on every route.
 */
/** Two-pane kicks in at this width (spec: WindowSizeClass Expanded lower bound). */
private val TWO_PANE_MIN_WIDTH = 840.dp

/** Routes hosted INSIDE the tablet two-pane; every other route renders full-screen. */
private val TWO_PANE_ROUTES: Set<NavRoute> = setOf(
    NavRoute.PANEL, NavRoute.SCANNER, NavRoute.POSITIONS, NavRoute.SIGNALS,
)

@Composable
fun MobileShell(container: AppContainer) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    val nav: NavHostController = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentSlug = backStackEntry?.destination?.route
    val currentRoute = NavRoute.values().firstOrNull { it.slug == currentSlug }
        ?: NavRoute.Default

    Scaffold(
        topBar = {
            Column {
                MobileTopBar(pageTitle = strings.routeLabel(currentRoute))
                PulseStrip(container = container)
            }
        },
        bottomBar = {
            MobileBottomBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (route.slug != currentSlug) {
                        nav.navigate(route.slug) {
                            popUpTo(NavRoute.Default.slug) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
        containerColor = DiveColors.RootBg,
    ) { padding: PaddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg)
                .padding(padding),
        ) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH &&
                // Two-pane hosts the 4 primary destinations; More-sheet routes
                // (Alarmlar, Portföy, Leaders, …) render full-screen so they
                // stay reachable on wide screens too.
                currentRoute in TWO_PANE_ROUTES
            if (twoPane) {
                TabletTwoPane(container = container)
            } else {
                PhoneNavHost(container = container, nav = nav)
            }

            // In-app alert banner — overlays the top of WHICHEVER layout is showing
            // (Task 2b); dismissed via container.dismissAlertBanner().
            AlertBannerHost(
                container = container,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** Phone layout: the classic single-pane navigation host. */
@Composable
private fun PhoneNavHost(container: AppContainer, nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = NavRoute.Default.slug,
    ) {
        composable(NavRoute.PANEL.slug) { PanelScreen(container) }
        composable(NavRoute.SCANNER.slug) {
            ScannerScreen(
                container = container,
                onSelectSymbol = { symbol ->
                    container.activeSymbol.value = symbol
                    nav.navigate(NavRoute.PANEL.slug) {
                        popUpTo(NavRoute.Default.slug) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
        composable(NavRoute.POSITIONS.slug) { PositionsScreen(container) }
        composable(NavRoute.SIGNALS.slug) { SignalsScreen(container) }
        composable(NavRoute.ALERTS.slug) {
            AlertsScreen(
                container = container,
                onOpenSymbol = { symbol ->
                    container.activeSymbol.value = symbol
                    nav.navigate(NavRoute.PANEL.slug) {
                        popUpTo(NavRoute.Default.slug) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        composable(NavRoute.PORTFOLIO.slug) { PortfolioScreen(container) }
        composable(NavRoute.PERFORMANCE.slug) { PerformanceScreen(container) }
        composable(NavRoute.LOGS.slug) { LogsScreen(container) }
        composable(NavRoute.APPEARANCE.slug) { AppearanceScreen() }
        composable(NavRoute.SETTINGS.slug) { SettingsScreen(container) }
    }
}

/**
 * Tablet layout: left = scanner list (SELECT stays in place — it only drives
 * the shared active symbol), right = the active symbol's detail with
 * Panel / OI·L/S / Signals as tabs. Each inner screen keeps its own stale
 * header + honesty chips. `key(activeSymbol)` remounts the detail VMs so a
 * selection change reloads that symbol's data instead of stale reuse.
 */
@Composable
private fun TabletTwoPane(container: AppContainer) {
    val activeSymbol by container.activeSymbol.collectAsStateWithLifecycle()
    var rightTab by remember { mutableIntStateOf(0) }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left pane: scanner list ─────────────────────────────────────
        Box(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight()
                .border(1.dp, DiveColors.Border, RoundedCornerShape(0.dp)),
        ) {
            ScannerScreen(
                container = container,
                onSelectSymbol = { symbol -> container.activeSymbol.value = symbol },
            )
        }

        // ── Right pane: active-symbol detail ────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTabRow(
                activeSymbol = activeSymbol,
                selected = rightTab,
                onSelect = { rightTab = it },
            )
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                key(activeSymbol, rightTab) {
                    when (rightTab) {
                        1 -> PositionsScreen(container)
                        2 -> SignalsScreen(container)
                        else -> PanelScreen(container)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTabRow(activeSymbol: String, selected: Int, onSelect: (Int) -> Unit) {
    val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current
    val tabs = listOf(strings.tabPanel, strings.tabOiLs, strings.tabSignals)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = activeSymbol,
            color = DiveColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.Mono,
        )
        tabs.forEachIndexed { idx, label ->
            val active = idx == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) DiveColors.Accent.copy(alpha = 0.18f) else DiveColors.BgCardHover)
                    .border(
                        1.dp,
                        if (active) DiveColors.Accent.copy(alpha = 0.6f) else DiveColors.Border,
                        RoundedCornerShape(6.dp),
                    )
                    .semantics {
                        role = Role.Tab
                        stateDescription = if (active) strings.selected else strings.notSelected
                    }
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    text = label,
                    color = if (active) DiveColors.Accent else DiveColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
            }
        }
    }
}
