package com.diveintocrypto.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 7 routes — paper-trading bindings stripped; each screen now consumes real
 * Binance public futures APIs. `inBottomBar = true` for the 4 primary
 * destinations; the rest live behind the "More" overflow sheet.
 *
 * `slug` is the internal NavController route key for each destination;
 * `label` is the user-facing display copy.
 */
enum class NavRoute(
    val slug: String,
    val label: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
    val inBottomBar: Boolean,
) {
    PANEL("panel", "Panel", Icons.Outlined.Dashboard, Icons.Rounded.Dashboard, inBottomBar = true),
    SCANNER("scanner", "Scanner", Icons.Outlined.Search, Icons.Rounded.Search, inBottomBar = true),
    POSITIONS("positions", "OI · L/S", Icons.Outlined.PieChart, Icons.Rounded.PieChart, inBottomBar = true),
    SIGNALS("signals", "Signals", Icons.Outlined.Notifications, Icons.Rounded.Notifications, inBottomBar = true),
    PERFORMANCE("performance", "Leaders", Icons.Outlined.QueryStats, Icons.Rounded.QueryStats, inBottomBar = false),
    LOGS("logs", "Network Log", Icons.AutoMirrored.Outlined.Article, Icons.AutoMirrored.Rounded.Article, inBottomBar = false),
    APPEARANCE("appearance", "Appearance", Icons.Outlined.Palette, Icons.Rounded.Palette, inBottomBar = false),
    SETTINGS("settings", "Settings", Icons.Outlined.Info, Icons.Rounded.Info, inBottomBar = false);

    companion object {
        val Default = SCANNER
        val BottomBarRoutes: List<NavRoute> = values().filter { it.inBottomBar }
        val OverflowRoutes: List<NavRoute> = values().filter { !it.inBottomBar }
    }
}
