package com.hexa.sendsize.compression

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition as MediaComposition
import androidx.media3.transformer.*
import com.hexa.sendsize.compression.*
import com.hexa.sendsize.conversion.*
import com.hexa.sendsize.model.*
import com.hexa.sendsize.navigation.*
import com.hexa.sendsize.settings.*
import com.hexa.sendsize.storage.*
import com.hexa.sendsize.ui.*
import com.hexa.sendsize.ui.components.*
import com.hexa.sendsize.ui.theme.SendSizeTheme
import com.hexa.sendsize.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.util.Locale
import kotlin.math.sin

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun startVideoCompression(
    context: Context, uri: Uri, targetMb: Float, removeAudio: Boolean = false,
    compressionPriority: String = "Exact",
    onProgress: (Float) -> Unit,
    onStatusUpdate: (String) -> Unit = {},
    onComplete: (File) -> Unit,
    onError: (String, File?) -> Unit,
    onLog: (String) -> Unit = {}
) {
    android.util.Log.d("SendSize", "startVideoCompression: uri=$uri, targetMb=$targetMb, removeAudio=$removeAudio, priority=$compressionPriority")
    onLog("startVideoCompression: uri=$uri, targetMb=$targetMb, removeAudio=$removeAudio, priority=$compressionPriority")
    
    val maxAttempts = 5
    
    fun runAttempt(attempt: Int) {
        val retriever = MediaMetadataRetriever()
        try {
            android.util.Log.d("SendSize", "Compression Attempt $attempt/$maxAttempts started for URI: $uri")
            onLog("Compression Pass $attempt/$maxAttempts started")
            onStatusUpdate("Pass $attempt/$maxAttempts: Calculating bitrate...")
            onLog("Pass $attempt/$maxAttempts: Calculating bitrate...")
            
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                android.util.Log.e("SendSize", "Failed to set data source for URI: $uri", e)
                onLog("ERROR: Failed to set data source for URI: $uri")
                onError("Could not read input file: ${e.message}", null)
                return
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            retriever.release()

            android.util.Log.d("SendSize", "Metadata: durationMs=$durationMs, width=$originalWidth, height=$originalHeight")
            onLog("Metadata: durationMs=$durationMs, width=$originalWidth, height=$originalHeight")

            if (durationMs <= 0) {
                android.util.Log.e("SendSize", "Invalid video duration: $durationMs")
                onLog("ERROR: Invalid video duration: $durationMs")
                onError("Invalid video duration: $durationMs ms", null)
                return
            }

            val durationS = durationMs / 1000f
            
            // Increasingly aggressive safety factors for each attempt
            val safetyFactor = when (attempt) {
                1 -> 0.90f
                2 -> 0.80f
                3 -> 0.70f
                4 -> 0.60f
                else -> 0.50f
            }

            val totalBudgetBits = targetMb * 1024 * 1024 * 8
            val safeBudgetBits = totalBudgetBits * safetyFactor
            
            val audioBitrate = if (removeAudio) 0 else 128000
            val videoBudgetBits = safeBudgetBits - (audioBitrate * durationS)
            
            // Calculate target video bitrate
            var targetVideoBitrate = (videoBudgetBits / durationS).toInt()
            
            // Minimum floor for bitrate to avoid complete garbage
            targetVideoBitrate = targetVideoBitrate.coerceIn(100_000, 50_000_000)

            android.util.Log.d("SendSize", "Bitrate Calculation: targetMb=$targetMb, durationS=$durationS, safetyFactor=$safetyFactor, targetVideoBitrate=$targetVideoBitrate")
            onLog("Bitrate Calculation: targetMb=$targetMb, durationS=$durationS, safetyFactor=$safetyFactor, targetVideoBitrate=$targetVideoBitrate")

            // Scaling logic: automatically reduce resolution if pass 1 failed or bitrate is too low
            var targetHeight = originalHeight
            
            if (compressionPriority == "Exact") {
                val minDim = if (originalWidth > 0 && originalHeight > 0) Math.min(originalWidth, originalHeight) else 0
                if (minDim > 0) {
                    when {
                        targetVideoBitrate < 400_000 -> { // Very low: 360p max
                            if (minDim > 360) targetHeight = (originalHeight * (360f / minDim)).toInt()
                        }
                        targetVideoBitrate < 1_000_000 -> { // Low: 480p max
                            if (minDim > 480) targetHeight = (originalHeight * (480f / minDim)).toInt()
                        }
                        targetVideoBitrate < 2_500_000 -> { // Medium: 720p max
                            if (minDim > 720) targetHeight = (originalHeight * (720f / minDim)).toInt()
                        }
                    }
                }
                
                // Aggressively reduce height for retry passes to force encoder compliance
                if (attempt > 1) {
                    val reduction = 1.0f - (0.25f * (attempt - 1)) // Pass 2: 75%, Pass 3: 50%, Pass 4: 25%
                    targetHeight = (targetHeight * reduction.coerceAtLeast(0.2f)).toInt()
                }
            }
            
            // Ensure height is even
            targetHeight = (targetHeight / 2) * 2
            android.util.Log.d("SendSize", "Target resolution height: $targetHeight")
            onLog("Target resolution height: $targetHeight")

            val outputFile = File(context.cacheDir, "comp_${System.currentTimeMillis()}_at$attempt.mp4")
            android.util.Log.d("SendSize", "Output file path: ${outputFile.absolutePath}")
            onLog("Output file path: ${outputFile.absolutePath}")

            val statusMsg = when(attempt) {
                1 -> "Pass 1/$maxAttempts: Compressing..."
                else -> "Pass $attempt/$maxAttempts: Reducing bitrate..."
            }
            onStatusUpdate(statusMsg)
            onLog(statusMsg)

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(targetVideoBitrate)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: MediaComposition, exportResult: ExportResult) {
                        val actualSize = outputFile.length()
                        android.util.Log.d("SendSize", "Pass $attempt complete. Actual size: $actualSize bytes")
                        onLog("Pass $attempt complete. Actual size: ${formatSize(actualSize)}")
                        
                        if (!outputFile.exists() || actualSize == 0L) {
                            android.util.Log.e("SendSize", "Output file is missing or empty")
                            onLog("ERROR: Output file is missing or empty")
                            onError("Output file was not created successfully", null)
                            return
                        }

                        val targetSizeBytes = (targetMb * 1024 * 1024).toLong()
                        // Strict tolerance for "Exact" mode (2%)
                        val toleranceLimit = targetSizeBytes * 1.02 
                        
                        if (actualSize > toleranceLimit && attempt < maxAttempts) {
                            android.util.Log.w("SendSize", "Size exceeds limit ($actualSize > $toleranceLimit). Retrying...")
                            onLog("Size exceeds limit (${formatSize(actualSize)} > ${formatSize(toleranceLimit.toLong())}). Retrying...")
                            outputFile.delete()
                            runAttempt(attempt + 1)
                        } else if (actualSize > toleranceLimit && attempt >= maxAttempts) {
                            android.util.Log.e("SendSize", "Final output too large after $maxAttempts attempts.")
                            onLog("ERROR: Could not reach target size. Final output was ${formatSize(actualSize)}, target was ${formatSize(targetSizeBytes)}.")
                            onError("Could not reach target size. Final output was ${formatSize(actualSize)}, target was ${formatSize(targetSizeBytes)}.", outputFile)
                        } else {
                            android.util.Log.d("SendSize", "Compression successful: ${outputFile.absolutePath}")
                            onLog("Compression successful: ${outputFile.absolutePath}")
                            onStatusUpdate("Finalizing...")
                            onLog("Finalizing...")
                            onComplete(outputFile)
                        }
                    }
                    override fun onError(composition: MediaComposition, exportResult: ExportResult, exception: ExportException) {
                        android.util.Log.e("SendSize", "Transformer Error: ${exception.message}", exception)
                        onLog("ERROR: Transformer Error: ${exception.message}")
                        onError("Encoding error: ${exception.message}", null)
                    }
                })
                .build()

            val mediaItem = MediaItem.fromUri(uri)
            val videoEffects = mutableListOf<Effect>()
            if (targetHeight > 0 && targetHeight != originalHeight) {
                videoEffects.add(Presentation.createForHeight(targetHeight))
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(removeAudio)
                .setEffects(Effects(listOf(), videoEffects))
                .build()

            try {
                transformer.start(editedMediaItem, outputFile.absolutePath)
                android.util.Log.d("SendSize", "Transformer started successfully")
                onLog("Transformer started successfully")
            } catch (e: Exception) {
                android.util.Log.e("SendSize", "Failed to start transformer", e)
                onLog("ERROR: Failed to start transformer: ${e.message}")
                onError("Could not start compression: ${e.message}", null)
                return
            }

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val progressRunnable = object : Runnable {
                override fun run() {
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        handler.postDelayed(this, 500)
                    }
                }
            }
            handler.post(progressRunnable)
        } catch (e: Exception) {
            android.util.Log.e("SendSize", "Init error in runAttempt", e)
            onLog("ERROR: Init error in runAttempt: ${e.message}")
            onError("Initialization error: ${e.message}", null)
        }
    }
    
    runAttempt(1)
}

