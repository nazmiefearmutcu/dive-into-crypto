package com.diveintocrypto.android.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.diveintocrypto.android.share.ShareVerdictRow
import com.diveintocrypto.android.share.shareVerdict
import kotlinx.coroutines.launch

/**
 * Android actual: converts the common payload into a [ShareVerdictRow] and
 * fires the system share sheet. The render + PNG write run ASYNC on
 * Dispatchers.IO (inside [shareVerdict]) so the UI lane never blocks on disk
 * I/O; the returned lambda launches that job on the composition's scope and
 * returns immediately.
 *
 * true = the async share job was dispatched (legacy callers keep compiling;
 * the synchronous render success signal no longer exists). Failures inside the
 * job are swallowed by [shareVerdict]'s honest null envelope and never crash
 * the UI.
 */
@Composable
actual fun rememberShareVerdict(): (ShareVerdictPayload) -> Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { payload ->
        scope.launch {
            shareVerdict(
                context,
                ShareVerdictRow(
                    symbol = payload.symbol,
                    verdict = payload.verdict,
                    confidence = payload.confidence,
                    perTf = payload.perTf,
                    timestampMs = payload.timestampMs,
                ),
            )
        }
        true
    }
}
