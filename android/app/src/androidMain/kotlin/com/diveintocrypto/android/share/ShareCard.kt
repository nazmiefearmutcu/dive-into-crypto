package com.diveintocrypto.android.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.diveintocrypto.android.platform.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * One share-card payload: the verdict snapshot to render as a PNG.
 * [perTf] drives the TF mini-heat strip (colored boxes, one per timeframe).
 */
data class ShareVerdictRow(
    val symbol: String,
    /** Signal name (BUY / SELL / STRONG_BUY / STRONG_SELL / NEUTRAL). */
    val verdict: String,
    /** 0..100. */
    val confidence: Int,
    /** (timeframe, signal name) pairs in display order; empty = no heat strip. */
    val perTf: List<Pair<String, String>> = emptyList(),
    /** Wall-clock ms of the verdict. */
    val timestampMs: Long,
)

/**
 * Offscreen verdict-card renderer: a simple dark column layout (symbol, verdict,
 * confidence bar, TF mini-heat boxes, timestamp, watermark) painted into a
 * [Bitmap] via a plain android.graphics [Canvas] — no window / PixelCopy /
 * hardware renderer needed, so it also works from a background worker.
 */
object VerdictCardRenderer {

    // Palette (inline, mirrors the widget's dark scheme).
    private val BG = 0xFF0B1220.toInt()
    private val CARD = 0xFF111A2C.toInt()
    private val ACCENT = 0xFF6EA8FE.toInt()
    private val TEXT = 0xFFE6EDF7.toInt()
    private val TEXT_DIM = 0xFF8A94A6.toInt()
    private val GREEN = 0xFF3FB68B.toInt()
    private val RED = 0xFFE5534B.toInt()
    private val GRAY = 0xFF3A4356.toInt()

    /** Default render width (px). Height derives from content. */
    const val DEFAULT_WIDTH = 1080

    fun verdictColor(verdict: String): Int = when (verdict) {
        "STRONG_BUY", "BUY" -> GREEN
        "STRONG_SELL", "SELL" -> RED
        else -> TEXT_DIM
    }

    /**
     * PURE draw (no I/O): renders [row] into a fresh ARGB bitmap.
     * Height = base block + one heat row when TF cells exist.
     */
    fun render(row: ShareVerdictRow, width: Int = DEFAULT_WIDTH): Bitmap {
        val heatRows = if (row.perTf.isEmpty()) 0 else 1
        val height = 470 + heatRows * 170
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = width / 1080f // scale factor so any width renders proportionally

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        canvas.drawRoundRect(RectF(28 * s, 28 * s, width - 28 * s, height - 28 * s), 28 * s, 28 * s, cardPaint)

        // Symbol (header).
        canvas.drawText(
            row.symbol, 72 * s, 150 * s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT; textSize = 72 * s; typeface = Typeface.MONOSPACE
                isFakeBoldText = true
            },
        )

        // Verdict (colored) + confidence %.
        val verdictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = verdictColor(row.verdict); textSize = 54 * s; typeface = Typeface.MONOSPACE
            isFakeBoldText = true
        }
        canvas.drawText(row.verdict, 72 * s, 240 * s, verdictPaint)
        canvas.drawText(
            "%${row.confidence}", width - 72 * s, 240 * s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT; textAlign = Paint.Align.RIGHT; textSize = 54 * s
                typeface = Typeface.MONOSPACE; isFakeBoldText = true
            },
        )

        // Confidence bar (track + fill).
        val barTop = 290 * s
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRAY }
        canvas.drawRoundRect(RectF(72 * s, barTop, width - 72 * s, barTop + 26 * s), 13 * s, 13 * s, track)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        val fillW = (width - 144 * s) * (row.confidence.coerceIn(0, 100) / 100.0).toFloat()
        if (fillW > 1f) {
            canvas.drawRoundRect(RectF(72 * s, barTop, 72 * s + fillW, barTop + 26 * s), 13 * s, 13 * s, fill)
        }

        var y = barTop + 110 * s

        // TF mini-heat strip: one colored box + label per timeframe.
        if (heatRows > 0) {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_DIM; textSize = 26 * s; textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
            }
            val n = row.perTf.size
            val gap = 12 * s
            val boxW = ((width - 144 * s) - gap * (n - 1)) / n
            val boxH = 96 * s
            row.perTf.forEachIndexed { i, (tf, signal) ->
                val left = 72 * s + i * (boxW + gap)
                boxPaint.color = when {
                    signal == "STRONG_BUY" || signal == "BUY" -> GREEN
                    signal == "STRONG_SELL" || signal == "SELL" -> RED
                    signal == "N/A" || signal == "ERROR" -> GRAY
                    else -> TEXT_DIM
                }
                canvas.drawRoundRect(RectF(left, y, left + boxW, y + boxH), 10 * s, 10 * s, boxPaint)
                canvas.drawText(tf, left + boxW / 2, y + boxH + 34 * s, labelPaint)
            }
            y += boxH + 80 * s
        }

        // Timestamp (local time).
        canvas.drawText(
            formatTime(row.timestampMs, "yyyy-MM-dd HH:mm"), 72 * s, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DIM; textSize = 28 * s; typeface = Typeface.MONOSPACE },
        )

        // Watermark (bottom-right, dimmed).
        canvas.drawText(
            "dive-into-crypto", width - 72 * s, height - 52 * s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_DIM; textSize = 26 * s; textAlign = Paint.Align.RIGHT
                typeface = Typeface.MONOSPACE
            },
        )
        return bitmap
    }
}

/**
 * Renders the card, writes the PNG under `cacheDir/share_cards/` and fires the
 * system share sheet (ACTION_SEND image/png via FileProvider).
 *
 * SUSPENDING + off the UI thread: the bitmap render and the disk write run on
 * [Dispatchers.IO] (Compose callbacks must never block on file I/O). PNGs in
 * `share_cards/` older than [SHARE_CARD_MAX_AGE_MS] are pruned before a new
 * card is written so the cache cannot grow unbounded.
 *
 * @return the shared content URI (null when rendering/persisting failed — the
 *         caller can surface the failure honestly; nothing is faked).
 */
suspend fun shareVerdict(context: Context, row: ShareVerdictRow): android.net.Uri? {
    return try {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share_cards").apply { mkdirs() }
            pruneOldShareCards(dir)
            val bitmap = VerdictCardRenderer.render(row)
            val f = File(dir, "verdict_${row.symbol}_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            f
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Karar kartını paylaş").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        uri
    } catch (_: Throwable) {
        null
    }
}

/**
 * Deletes share-card PNGs older than [maxAgeMs] (best-effort — a failed delete
 * never blocks the new card).
 */
private fun pruneOldShareCards(dir: File, maxAgeMs: Long = SHARE_CARD_MAX_AGE_MS) {
    val cutoff = System.currentTimeMillis() - maxAgeMs
    runCatching {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".png") && f.lastModified() < cutoff) f.delete()
        }
    }
}

/** Share-card PNGs are disposable — prune anything older than 24h. */
const val SHARE_CARD_MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L
