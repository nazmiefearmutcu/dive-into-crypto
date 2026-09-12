package com.diveintocrypto.android.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.FiredAlert
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/**
 * In-app alert banner (Task 2b): when [AppContainer.alertBanner] is non-null a
 * hairline card slides in at the TOP of the current screen with a dismiss ("×")
 * wired to [AppContainer.dismissAlertBanner]. Rendered once, above the NavHost
 * (see MobileShell) — so it overlays whichever screen is showing. The OS
 * notification (AndroidAlertNotifier) is the other half; this banner also works
 * when notifications are denied.
 */
@Composable
fun AlertBannerHost(container: AppContainer, modifier: Modifier = Modifier) {
    val banner by container.alertBanner.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier,
    ) {
        val fired = banner ?: return@AnimatedVisibility
        val rule = container.rules.value.firstOrNull { it.id == fired.ruleId }
        val accent = bannerColor(fired)
        val strings = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DiveDims.Radius))
                .background(DiveColors.BgCard)
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(DiveDims.Radius))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3px accent strip
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "${strings.alarmPrefix} · ${fired.symbol}",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    fontFamily = DiveFonts.body,
                )
                Text(
                    text = AlertLabels.ruleLineLabel(rule),
                    color = DiveColors.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = DiveFonts.body,
                )
            }
            // Dismiss
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { container.dismissAlertBanner() }
                    .semantics {
                        role = Role.Button
                        contentDescription = strings.a11yDismissBanner
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "×",
                    color = DiveColors.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun bannerColor(fired: FiredAlert): Color = when (fired.kind) {
    AlertKind.PRICE_ABOVE -> DiveColors.Green
    AlertKind.PRICE_BELOW -> DiveColors.Red
    AlertKind.OI_SPIKE_PCT -> DiveColors.Warn
    AlertKind.CONFIDENCE_ABOVE -> DiveColors.Cyan
    AlertKind.VERDICT -> DiveColors.Accent
}
