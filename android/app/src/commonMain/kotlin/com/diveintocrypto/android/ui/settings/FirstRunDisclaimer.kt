package com.diveintocrypto.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/** KeyValueStore key: the user has read and accepted the not-investment-advice notice. */
const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted_v1"

/**
 * Shows the full not-investment-advice notice once, on first launch, and keeps the
 * app behind it until the user taps accept. The acceptance is persisted, so it is
 * shown again only after the app data is cleared.
 */
@Composable
fun FirstRunDisclaimerGate(container: AppContainer, content: @Composable () -> Unit) {
    val kv = container.keyValueStore
    var accepted by remember { mutableStateOf(readAccepted(kv)) }

    content()

    if (!accepted) {
        val strings = LocalDiveStrings.current
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(DiveDims.Radius))
                    .background(DiveColors.BgCard)
                    .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = strings.disclaimerTitle,
                    color = DiveColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DiveFonts.body,
                )
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = strings.disclaimerBody,
                        color = DiveColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DiveColors.Accent)
                        .semantics { role = Role.Button }
                        .clickable {
                            writeAccepted(kv)
                            accepted = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = strings.disclaimerAccept,
                        color = DiveColors.Bg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DiveFonts.body,
                    )
                }
            }
        }
    }
}

private fun readAccepted(kv: com.diveintocrypto.android.data.KeyValueStore): Boolean = try {
    kv.getString(KEY_DISCLAIMER_ACCEPTED, null) == "1"
} catch (e: Exception) {
    false
}

private fun writeAccepted(kv: com.diveintocrypto.android.data.KeyValueStore) {
    try {
        kv.putString(KEY_DISCLAIMER_ACCEPTED, "1")
    } catch (e: Exception) {
        // Persisting failed: the notice simply appears again next launch.
    }
}
