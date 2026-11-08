package com.diveintocrypto.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.diveintocrypto.android.ui.i18n.LocalDiveStrings
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveDims
import com.diveintocrypto.android.ui.theme.DiveFonts

/** Shared dialog chrome: card surface, title, scrollable body, and a footer button row. */
@Composable
private fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val strings = LocalDiveStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DiveDims.Radius))
                .background(DiveColors.BgCard)
                .border(1.dp, DiveColors.Border, RoundedCornerShape(DiveDims.Radius))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = DiveColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontFamily = DiveFonts.body,
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = body,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                footer()
                DialogButton(label = strings.setDialogClose, onClick = onDismiss, primary = false)
            }
        }
    }
}

/** Text button with a 48dp minimum touch target (PS-30). */
@Composable
internal fun DialogButton(label: String, onClick: () -> Unit, primary: Boolean) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) DiveColors.Accent else DiveColors.BgCardHover)
            .border(1.dp, if (primary) DiveColors.Accent else DiveColors.Border, RoundedCornerShape(8.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) DiveColors.Bg else DiveColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = DiveFonts.body,
        )
    }
}

/** Full privacy policy text (TR/EN from the catalog). */
@Composable
fun PrivacyPolicyDialog(
    onDismiss: () -> Unit,
    onOpenOnline: (() -> Unit)?,
) {
    val strings = LocalDiveStrings.current
    InfoDialog(
        title = strings.setPrivacyTitle,
        onDismiss = onDismiss,
        footer = {
            if (onOpenOnline != null) {
                DialogButton(label = strings.setPrivacyOpenOnline, onClick = onOpenOnline, primary = false)
            }
        },
    ) {
        Text(
            text = strings.setPrivacyBody,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

/** Open-source notices for the bundled dependencies (all Apache License 2.0). */
@Composable
fun OssLicensesDialog(onDismiss: () -> Unit) {
    val strings = LocalDiveStrings.current
    InfoDialog(title = strings.setLicensesTitle, onDismiss = onDismiss) {
        Text(
            text = strings.setLicensesIntro,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        OSS_LIBRARIES.forEach { lib ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${lib.name} ${lib.version}",
                    color = DiveColors.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = DiveFonts.body,
                )
                Text(
                    text = "${lib.coordinates} · ${lib.license}",
                    color = DiveColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Confirmation before the destructive local-data wipe. */
@Composable
fun ClearLocalDataDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalDiveStrings.current
    InfoDialog(
        title = strings.setClearDataRow,
        onDismiss = onDismiss,
        footer = {
            DialogButton(label = strings.setClearConfirm, onClick = onConfirm, primary = true)
        },
    ) {
        Text(
            text = strings.setClearDataHint,
            color = DiveColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

/** One bundled open-source library. */
internal data class OssLibrary(
    val name: String,
    val coordinates: String,
    val version: String,
    val license: String = "Apache License 2.0",
)

/**
 * Runtime / UI dependencies shipped in the app, taken from gradle/libs.versions.toml.
 * Test-only libraries (JUnit, coroutines-test, ktor-client-mock) are not distributed and are omitted.
 */
internal val OSS_LIBRARIES: List<OssLibrary> = listOf(
    OssLibrary("Kotlin", "org.jetbrains.kotlin", "2.0.21"),
    OssLibrary("Compose Multiplatform", "org.jetbrains.compose", "1.7.3"),
    OssLibrary("kotlinx.serialization", "org.jetbrains.kotlinx:kotlinx-serialization-json", "1.7.3"),
    OssLibrary("kotlinx.coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines-core", "1.9.0"),
    OssLibrary("kotlinx.datetime", "org.jetbrains.kotlinx:kotlinx-datetime", "0.6.1"),
    OssLibrary("kotlinx.atomicfu", "org.jetbrains.kotlinx:atomicfu", "0.25.0"),
    OssLibrary("Ktor client", "io.ktor:ktor-client-core", "3.0.3"),
    OssLibrary("Ktor OkHttp engine", "io.ktor:ktor-client-okhttp", "3.0.3"),
    OssLibrary("Ktor websockets", "io.ktor:ktor-client-websockets", "3.0.3"),
    OssLibrary("AndroidX Lifecycle (JetBrains port)", "org.jetbrains.androidx.lifecycle", "2.8.4"),
    OssLibrary("AndroidX Navigation (JetBrains port)", "org.jetbrains.androidx.navigation:navigation-compose", "2.8.0-alpha10"),
    OssLibrary("AndroidX Core KTX", "androidx.core:core-ktx", "1.13.1"),
    OssLibrary("AndroidX Activity Compose", "androidx.activity:activity-compose", "1.9.3"),
    OssLibrary("AndroidX WorkManager", "androidx.work:work-runtime-ktx", "2.9.1"),
    OssLibrary("AndroidX Glance (App Widgets)", "androidx.glance:glance-appwidget", "1.1.1"),
    OssLibrary("Material Components for Android", "com.google.android.material:material", "1.12.0"),
)
