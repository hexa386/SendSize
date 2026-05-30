package com.hexa.sendsize.conversion

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
fun startConversion(
    context: Context, uri: Uri, format: String, extractAudio: Boolean,
    onProgress: (Float) -> Unit, onComplete: (File) -> Unit, onError: (String) -> Unit,
    onLog: (String) -> Unit = {}
) {
    onLog("Starting conversion: URI=$uri, format=$format, extractAudio=$extractAudio")
    val ext = format.lowercase()
    val outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.$ext")
    
    val audioMimeType = when (format.uppercase()) {
        "MP3" -> MimeTypes.AUDIO_MPEG
        "AAC" -> MimeTypes.AUDIO_AAC
        "WAV" -> MimeTypes.AUDIO_RAW
        "FLAC" -> MimeTypes.AUDIO_FLAC
        "OGG" -> MimeTypes.AUDIO_OGG
        else -> MimeTypes.AUDIO_AAC
    }

    val transformer = Transformer.Builder(context).apply {
        if (extractAudio) {
            setAudioMimeType(audioMimeType)
        } else {
            // For video conversion, use H.264 as a safe default for most formats
            setVideoMimeType(MimeTypes.VIDEO_H264)
        }
    }
    .addListener(object : Transformer.Listener {
        override fun onCompleted(composition: MediaComposition, exportResult: ExportResult) {
            onComplete(outputFile)
        }
        override fun onError(composition: MediaComposition, exportResult: ExportResult, exception: ExportException) {
            onError(exception.message ?: "Error")
        }
    })
    .build()

    val mediaItem = MediaItem.fromUri(uri)
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setRemoveVideo(extractAudio)
        .build()
    
    transformer.start(editedMediaItem, outputFile.absolutePath)
    onLog("Transformer started: output=${outputFile.absolutePath}")
    
    val handler = Handler(Looper.getMainLooper())
    val holder = ProgressHolder()
    handler.post(object : Runnable {
        override fun run() {
            if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                onProgress(holder.progress / 100f)
                handler.postDelayed(this, 500)
            }
        }
    })
}

suspend fun convertImage(context: Context, uri: Uri, targetFormat: String): File = withContext(Dispatchers.IO) {
    val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Fail")
    val bitmap = BitmapFactory.decodeStream(inputStream)
    inputStream.close()
    
    val format = when (targetFormat.uppercase()) {
        "JPEG", "JPG" -> Bitmap.CompressFormat.JPEG
        "PNG" -> Bitmap.CompressFormat.PNG
        "WEBP" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
    
    val ext = targetFormat.lowercase()
    val outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.$ext")
    FileOutputStream(outputFile).use { out ->
        bitmap.compress(format, 100, out)
    }
    outputFile
}

