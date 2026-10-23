package app.videocompressor

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

private data class Preset(val height: Int, val bitrate: Int)

private data class SourceInfo(
    val name: String,
    val sizeBytes: Long,
    val displayHeight: Int,
    val bitrate: Int
)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var btnPick: Button
    private lateinit var btnCompress: Button
    private lateinit var btnCancel: Button
    private lateinit var tvSelected: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rgQuality: RadioGroup
    private lateinit var cbHevc: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private var selected: List<Uri> = emptyList()
    private var transformer: Transformer? = null
    private var cancelled = false

    private var totalBefore = 0L
    private var totalAfter = 0L
    private var doneCount = 0
    private var failedCount = 0

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            selected = uris
            tvSelected.text = "${uris.size} video(s) selected"
            btnCompress.isEnabled = true
        }
    }

    private val progressPoller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                progressBar.progress = progressHolder.progress
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPick = findViewById(R.id.btnPick)
        btnCompress = findViewById(R.id.btnCompress)
        btnCancel = findViewById(R.id.btnCancel)
        tvSelected = findViewById(R.id.tvSelected)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        progressBar = findViewById(R.id.progressBar)
        rgQuality = findViewById(R.id.rgQuality)
        cbHevc = findViewById(R.id.cbHevc)

        btnPick.setOnClickListener {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
        btnCompress.setOnClickListener { startBatch() }
        btnCancel.setOnClickListener {
            cancelled = true
            transformer?.cancel()
            finishBatch()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressPoller)
        transformer?.cancel()
        super.onDestroy()
    }

    private fun currentPreset(): Preset = when (rgQuality.checkedRadioButtonId) {
        R.id.rbSmall -> Preset(480, 1_000_000)
        R.id.rbHigh -> Preset(1080, 4_500_000)
        else -> Preset(720, 2_200_000)
    }

    private fun setBusy(busy: Boolean) {
        btnPick.isEnabled = !busy
        btnCompress.isEnabled = !busy && selected.isNotEmpty()
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        rgQuality.isEnabled = !busy
        for (i in 0 until rgQuality.childCount) rgQuality.getChildAt(i).isEnabled = !busy
        cbHevc.isEnabled = !busy
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startBatch() {
        cancelled = false
        totalBefore = 0L
        totalAfter = 0L
        doneCount = 0
        failedCount = 0
        tvLog.text = ""
        setBusy(true)
        processNext(0)
    }

    private fun processNext(index: Int) {
        if (cancelled || index >= selected.size) {
            finishBatch()
            return
        }

        val uri = selected[index]
        val info = readInfo(uri)
        val preset = currentPreset()
        tvStatus.text = "Compressing ${index + 1} of ${selected.size}: ${info.name}"
        progressBar.progress = 0

        val cacheFile = File(cacheDir, "compressed_$index.mp4")
        if (cacheFile.exists()) cacheFile.delete()

        val effects = mutableListOf<Effect>()
        if (info.displayHeight > preset.height) {
            effects.add(Presentation.createForHeight(preset.height))
        }

        val targetBitrate = if (info.bitrate > 0) {
            minOf(preset.bitrate, (info.bitrate * 0.8).toInt()).coerceAtLeast(300_000)
        } else {
            preset.bitrate
        }

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(targetBitrate).build()
            )
            .setEnableFallback(true)
            .build()

        val mime = if (cbHevc.isChecked) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                handler.removeCallbacks(progressPoller)
                tvStatus.text = "Saving ${info.name}..."
                val outName = outputName(info.name)
                thread {
                    val savedOk = saveToMovies(cacheFile, outName)
                    val outSize = cacheFile.length()
                    cacheFile.delete()
                    runOnUiThread {
                        if (savedOk) {
                            doneCount++
                            totalBefore += info.sizeBytes
                            totalAfter += outSize
                            appendLog(
                                "OK  ${info.name}: ${fmt(info.sizeBytes)} -> ${fmt(outSize)}"
                            )
                        } else {
                            failedCount++
                            appendLog("FAILED to save ${info.name}")
                        }
                        processNext(index + 1)
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                handler.removeCallbacks(progressPoller)
                cacheFile.delete()
                if (!cancelled) {
                    failedCount++
                    appendLog("FAILED ${info.name}: ${exportException.message}")
                }
                processNext(index + 1)
            }
        }

        val t = Transformer.Builder(this)
            .setVideoMimeType(mime)
            .setEncoderFactory(encoderFactory)
            .addListener(listener)
            .build()
        transformer = t

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), effects))
            .build()

        try {
            t.start(edited, cacheFile.absolutePath)
            handler.post(progressPoller)
        } catch (e: Exception) {
            failedCount++
            appendLog("FAILED ${info.name}: ${e.message}")
            processNext(index + 1)
        }
    }

    private fun finishBatch() {
        handler.removeCallbacks(progressPoller)
        transformer = null
        setBusy(false)
        progressBar.progress = 0

        tvStatus.text = when {
            cancelled -> "Cancelled. $doneCount video(s) were saved before stopping."
            doneCount == 0 -> "Nothing was compressed."
            else -> {
                val saved = totalBefore - totalAfter
                val pct = if (totalBefore > 0) saved * 100.0 / totalBefore else 0.0
                String.format(
                    Locale.US,
                    "Done: %d video(s), saved %s (%.0f%% smaller). Find them in Movies/Compressed.",
                    doneCount, fmt(saved.coerceAtLeast(0)), pct.coerceAtLeast(0.0)
                )
            }
        }
        if (failedCount > 0) appendLog("$failedCount video(s) failed.")
    }

    private fun readInfo(uri: Uri): SourceInfo {
        var name = "video"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0) size = c.getLong(si)
            }
        }

        var height = 0
        var bitrate = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            height = if (rot == 90 || rot == 270) w else h
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            // Metadata is optional; compression still works without it.
        } finally {
            r.release()
        }
        return SourceInfo(name, size, height, bitrate)
    }

    private fun outputName(original: String): String {
        val base = original.substringBeforeLast('.', original)
        return "${base}_compressed.mp4"
    }

    private fun saveToMovies(file: File, displayName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Compressed")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return false
            }
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun appendLog(line: String) {
        tvLog.text = if (tvLog.text.isEmpty()) line else "${tvLog.text}\n$line"
    }

    private fun fmt(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
        else String.format(Locale.US, "%.1f MB", mb)
    }
}
