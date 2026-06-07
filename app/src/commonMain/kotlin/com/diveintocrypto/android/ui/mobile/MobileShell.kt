package com.diveintocrypto.android.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.ui.logs.LogsScreen
import com.diveintocrypto.android.ui.nav.NavRoute
import com.diveintocrypto.android.ui.panel.PanelScreen
import com.diveintocrypto.android.ui.performance.PerformanceScreen
import com.diveintocrypto.android.ui.positions.PositionsScreen
import com.diveintocrypto.android.ui.settings.SettingsScreen
import com.diveintocrypto.android.ui.settings.AppearanceScreen
import com.diveintocrypto.android.ui.signals.SignalsScreen
import com.diveintocrypto.android.ui.scanner.ScannerScreen
import com.diveintocrypto.android.ui.theme.DiveColors

/**
 * Mobile shell — 7-screen scanner + market-data app after paper-mode
 * removal. Each non-scanner screen consumes Binance public APIs:
 *   - Panel       → live klines + 12-TF consensus on active symbol
 *   - Scanner     → multi-TF cross-rank scanner (existing)
 *   - Positions   → Open Interest + Top Long/Short Ratio
 *   - Signals     → 15 indicators detail table for active symbol
 *   - Performance → 24h gainers/losers leaderboard
 *   - Logs        → live HTTP activity log
 *   - Settings    → theme + about
 */
@Composable
fun MobileShell(container: AppContainer) {
    val nav: NavHostController = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentSlug = backStackEntry?.destination?.route
    val currentRoute = NavRoute.values().firstOrNull { it.slug == currentSlug }
        ?: NavRoute.Default

    Scaffold(
        topBar = { MobileTopBar(pageTitle = currentRoute.label) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DiveColors.RootBg)
                .padding(padding),
        ) {
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
                composable(NavRoute.PERFORMANCE.slug) { PerformanceScreen(container) }
                composable(NavRoute.LOGS.slug) { LogsScreen(container) }
                composable(NavRoute.APPEARANCE.slug) { AppearanceScreen() }
                composable(NavRoute.SETTINGS.slug) { SettingsScreen(container) }
            }
        }
    }
}
