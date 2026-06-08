package com.diveintocrypto.android.ui.panel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims

/**
 * .card — translation of dashboard/static/style.css:97-111.
 *   background: var(--bg-card); border: 1px solid var(--border);
 *   border-radius: var(--radius); padding: 18px 20px; margin-bottom: 16px;
 *   h3: 13px, uppercase, letter-spacing 0.5px, color var(--text-muted), margin-bottom 14px.
 */
@Composable
fun DiveCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DiveDims.Radius))
            .background(DiveColors.BgCard)
            .border(width = 1.dp, color = DiveColors.Border, shape = RoundedCornerShape(DiveDims.Radius))
            .padding(horizontal = DiveDims.CardPadH, vertical = DiveDims.CardPadV)
    ) {
        if (title != null) {
            // .card h3 — style.css:104-111. Density tuned for phone: 12sp / 8dp
            // gap instead of desktop's 13sp / 14dp so the title eats less of
            // the limited card height.
            Text(
                text = title.uppercase(),
                color = DiveColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
    }
}
