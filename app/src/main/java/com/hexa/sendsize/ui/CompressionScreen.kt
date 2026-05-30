package com.hexa.sendsize.ui

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

@Composable
fun VideoCompressorTab(
    autoSave: Boolean,
    saveUri: String?,
    vibrations: Boolean,
    initialUri: Uri?,
    onConsumeUri: () -> Unit,
    onStatsUpdate: (String, Long, Long, String, String?) -> Unit,
    compressionPriority: String,
    technicalLogsEnabled: Boolean,
    onNavigateToConvert: (File) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Compression Modes
    var compressionMode by remember { mutableStateOf("Size") } // "Size" or "Percent"
    var targetMb by remember { mutableFloatStateOf(10f) }
    var targetPercent by remember { mutableFloatStateOf(50f) }

    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentFileIndex by remember { mutableIntStateOf(0) }
    var currentStatus by remember { mutableStateOf("Preparing...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorFile by remember { mutableStateOf<File?>(null) }
    var technicalLogs by remember { mutableStateOf("") }

    // File Info for display
    var fileName by remember { mutableStateOf("") }
    var originalSizeBytes by remember { mutableLongStateOf(0L) }
    var fileFormat by remember { mutableStateOf("") }
    var durationS by remember { mutableStateOf("") }

    // Restored Options
    var removeAudio by remember { mutableStateOf(false) }
    var removeMetadata by remember { mutableStateOf(false) }

    // Result Screen State
    var showResultScreen by remember { mutableStateOf(false) }
    var resultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var resultOriginalSizes by remember { mutableStateOf<List<Long>>(emptyList()) }

    fun updateFileInfo(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            val uri = uris[0]
            scope.launch(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val loadedName = getFileName(context, uri)
                    val loadedSize = getFileSize(context, uri)
                    val loadedFormat = context.contentResolver.getType(uri)?.split("/")?.lastOrNull()?.uppercase() ?: "???"
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    val loadedDuration = String.format(Locale.getDefault(), "%d:%02d", dur / 1000 / 60, (dur / 1000) % 60)
                    withContext(Dispatchers.Main) {
                        fileName = loadedName
                        originalSizeBytes = loadedSize
                        fileFormat = loadedFormat
                        durationS = loadedDuration
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SendSize", "Error getting file info", e)
                    withContext(Dispatchers.Main) { fileName = "Unknown" }
                } finally {
                    retriever.release()
                }
            }
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            selectedUris = listOf(initialUri)
            updateFileInfo(selectedUris)
            onConsumeUri()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (it.isNotEmpty()) {
            selectedUris = it
            updateFileInfo(it)
            showResultScreen = false
        }
    }

    if (showResultScreen && resultFiles.isNotEmpty()) {
        CompressionResultScreen(
            context = context,
            originalSizes = resultOriginalSizes,
            compressedFiles = resultFiles,
            vibrations = vibrations,
            onDone = {
                showResultScreen = false
                selectedUris = emptyList()
                resultFiles = emptyList()
                isProcessing = false
            },
            onReturnHome = { }
        )
    } else {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "Video Compression",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (isProcessing) {
                // Processing Screen
                ProcessingScreen(
                    fileName = if (selectedUris.size > 1) "Batch: ${currentFileIndex + 1}/${selectedUris.size}" else fileName,
                    progress = progress,
                    status = currentStatus,
                    technicalLogs = if (technicalLogsEnabled) technicalLogs else null,
                    onCancel = {
                        isProcessing = false
                        // In a real app, we'd cancel the Transformer here
                    }
                )
            } else if (errorMessage != null) {
                // Error Screen with Recovery Options
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Rounded.Warning, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Compression Issue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { 
                                    errorMessage = null
                                    errorFile = null
                                    // Trigger retry by re-running the click logic
                                    // Actually, it's better to just go back and let user click again or automate it.
                                    // For now, "Go Back" is essentially what's needed to retry with same/different settings.
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Retry / Adjust Settings")
                            }

                            if (errorFile != null && errorFile!!.exists() && errorFile!!.length() > 0) {
                                Button(
                                    onClick = {
                                        val file = errorFile!!
                                        val originalSize = originalSizeBytes // Use the first selected size for simplicity in recovery
                                        onStatsUpdate(file.name, originalSize, file.length(), "video", file.absolutePath)
                                        if (autoSave && saveUri != null) {
                                            autoSaveToFile(context, file, saveUri, file.name, "video/mp4")
                                        }
                                        resultFiles = listOf(file)
                                        resultOriginalSizes = listOf(originalSize)
                                        errorMessage = null
                                        errorFile = null
                                        showResultScreen = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Keep Anyway (${formatSize(errorFile!!.length())})")
                                }
                            }

                            OutlinedButton(
                                onClick = { 
                                    errorMessage = null
                                    errorFile = null
                                    selectedUris = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            } else {
                // Configuration Screen
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    onClick = { pickerLauncher.launch("video/*") }
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .padding(32.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                if (selectedUris.isEmpty()) Icons.Rounded.AddCircle else Icons.Rounded.CheckCircle,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = if (selectedUris.isEmpty()) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
                            )
                            Text(
                                if (selectedUris.isEmpty()) "Select Videos"
                                else if (selectedUris.size == 1) "Video Selected"
                                else "${selectedUris.size} Videos Selected"
                            )
                            if (selectedUris.isNotEmpty() && selectedUris.size == 1) {
                                Text(
                                    fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        if (selectedUris.isNotEmpty()) {
                            IconButton(
                                onClick = { selectedUris = emptyList() },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (selectedUris.isNotEmpty()) {
                    // Options Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text("Compression Mode", fontWeight = FontWeight.Bold)

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = compressionMode == "Size",
                                    onClick = { compressionMode = "Size" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) { Text("Target Size") }
                                SegmentedButton(
                                    selected = compressionMode == "Percent",
                                    onClick = { compressionMode = "Percent" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) { Text("Percentage") }
                            }

                            if (compressionMode == "Size") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val originalMb = originalSizeBytes / (1024f * 1024f)

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Target: ", fontWeight = FontWeight.Bold)
                                        OutlinedTextField(
                                            value = if (targetMb == 0f) "" else targetMb.toInt().toString(),
                                            onValueChange = {
                                                val value = it.filter { char -> char.isDigit() }.toFloatOrNull() ?: 0f
                                                targetMb = value.coerceAtMost(originalMb)
                                            },
                                            modifier = Modifier.width(100.dp),
                                            suffix = { Text("MB") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "Limit: ${originalMb.toInt()} MB",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Slider(
                                        value = targetMb,
                                        onValueChange = {
                                            targetMb = it.coerceAtMost(originalMb.coerceAtLeast(1f))
                                            if (vibrations) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        valueRange = 1f..originalMb.coerceAtLeast(100f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("Discord" to 10f, "Messenger" to 25f).forEach { (name, size) ->
                                            FilterChip(
                                                selected = targetMb == size,
                                                onClick = { targetMb = size.coerceAtMost(originalMb) },
                                                label = { Text(name) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Compression: ", fontWeight = FontWeight.Bold)
                                        OutlinedTextField(
                                            value = targetPercent.toInt().toString(),
                                            onValueChange = {
                                                val value = it.filter { char -> char.isDigit() }.toFloatOrNull() ?: 0f
                                                targetPercent = value.coerceIn(1f, 100f)
                                            },
                                            modifier = Modifier.width(100.dp),
                                            suffix = { Text("%") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                    }

                                    Slider(
                                        value = targetPercent,
                                        onValueChange = {
                                            targetPercent = it
                                            if (vibrations) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        valueRange = 5f..95f
                                    )
                                    val estimatedMb = (originalSizeBytes * (targetPercent / 100f)) / (1024f * 1024f)
                                    Text(
                                        "Estimated result: â‰ ${String.format(Locale.getDefault(), "%.1f", estimatedMb)} MB",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            OptionRow(
                                "Remove Audio",
                                removeAudio,
                                { removeAudio = it },
                                Icons.AutoMirrored.Rounded.VolumeOff
                            )
                            OptionRow("Remove Metadata", removeMetadata, { removeMetadata = it }, Icons.Rounded.Info)
                        }
                    }

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            isProcessing = true
                            currentFileIndex = 0
                            progress = 0f
                            currentStatus = "Preparing..."
                            technicalLogs = "Starting compression process...\n"
                            
                            val originalSizes = selectedUris.map { getFileSize(context, it) }
                            val finalTargetMbList = selectedUris.map { uri ->
                                if (compressionMode == "Percent") {
                                    val size = getFileSize(context, uri)
                                    (size * (targetPercent / 100f)) / (1024f * 1024f)
                                } else {
                                    targetMb
                                }
                            }

                            processBatchWithMode(
                                context,
                                selectedUris,
                                finalTargetMbList,
                                removeAudio,
                                compressionPriority,
                                onIndexUpdate = { currentFileIndex = it },
                                onProgress = { progress = it },
                                onStatusUpdate = { currentStatus = it },
                                onComplete = { files ->
                                    isProcessing = false
                                    files.forEachIndexed { i, file ->
                                        onStatsUpdate(
                                            file.name,
                                            originalSizes[i],
                                            file.length(),
                                            "video",
                                            file.absolutePath
                                        )
                                        if (autoSave && saveUri != null) {
                                            autoSaveToFile(context, file, saveUri, file.name, "video/mp4")
                                        }
                                    }
                                    showCompletionNotification(
                                        context,
                                        if (files.size > 1) "batch video" else files[0].name
                                    )
                                    resultFiles = files
                                    resultOriginalSizes = originalSizes
                                    showResultScreen = true
                                },
                                onError = { msg, file ->
                                    isProcessing = false
                                    errorMessage = msg
                                    errorFile = file
                                },
                                onLog = { technicalLogs += it + "\n" }
                            )
                        }
                    ) {
                        Text(if (selectedUris.size > 1) "Start Batch Processing" else "Start Compression")
                    }
                }
            }
        }
    }
}

@Composable
fun ProcessingScreen(
    fileName: String,
    progress: Float,
    status: String,
    technicalLogs: String? = null,
    onCancel: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "processingProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text("${(animatedProgress * 100).toInt()}%", fontWeight = FontWeight.Bold)
            }
            
            technicalLogs?.let { logs ->
                Spacer(Modifier.height(8.dp))
                LogPanel(logs)
            }
            
            Spacer(Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Cancel")
            }
        }
    }
}

fun processBatchWithMode(
    context: Context,
    uris: List<Uri>,
    targetMbs: List<Float>,
    removeAudio: Boolean,
    compressionPriority: String,
    onIndexUpdate: (Int) -> Unit,
    onProgress: (Float) -> Unit,
    onStatusUpdate: (String) -> Unit,
    onComplete: (List<File>) -> Unit,
    onError: (String, File?) -> Unit,
    onLog: (String) -> Unit = {}
) {
    android.util.Log.d("SendSize", "Starting batch processing for ${uris.size} files")
    onLog("Starting batch processing for ${uris.size} files")
    val results = mutableListOf<File>()
    var currentIndex = 0

    fun next() {
        if (currentIndex < uris.size) {
            android.util.Log.d("SendSize", "Processing file ${currentIndex + 1}/${uris.size}: ${uris[currentIndex]}")
            onLog("Processing file ${currentIndex + 1}/${uris.size}: ${uris[currentIndex]}")
            onIndexUpdate(currentIndex)
            onStatusUpdate("Preparing...")
            
            startVideoCompression(
                context,
                uris[currentIndex],
                targetMbs[currentIndex],
                removeAudio,
                compressionPriority,
                onProgress = {
                    onProgress(it)
                },
                onStatusUpdate = {
                    onStatusUpdate(it)
                    onLog(it)
                },
                onComplete = { file ->
                    android.util.Log.d("SendSize", "Completed file ${currentIndex + 1}: ${file.absolutePath}")
                    onLog("Completed file ${currentIndex + 1}: ${file.absolutePath}")
                    results.add(file)
                    currentIndex++
                    next()
                },
                onError = { error, file ->
                    android.util.Log.e("SendSize", "Error on file ${currentIndex + 1}: $error")
                    onLog("ERROR on file ${currentIndex + 1}: $error")
                    // Stop batch on error and notify UI
                    onError(error, file)
                },
                onLog = onLog
            )
        } else {
            android.util.Log.d("SendSize", "Batch processing complete. Total results: ${results.size}")
            onLog("Batch processing complete. Total results: ${results.size}")
            onComplete(results)
        }
    }
    next()
}


@Composable
fun PhotoCompressorTab(autoSave: Boolean, saveUri: String?, vibrations: Boolean, initialUri: Uri?, onConsumeUri: () -> Unit, onStatsUpdate: (String, Long, Long, String, String?) -> Unit, onNavigateToConvert: (File) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    
    // Compression Modes
    var compressionMode by remember { mutableStateOf("Size") }
    var targetMb by remember { mutableFloatStateOf(1f) }
    var targetPercent by remember { mutableFloatStateOf(50f) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<File>>(emptyList()) }
    val scope = rememberCoroutineScope()
    
    // File Info
    var fileName by remember { mutableStateOf("") }
    var originalSizeBytes by remember { mutableLongStateOf(0L) }
    var fileFormat by remember { mutableStateOf("") }

    // Restored Options
    var removeMetadata by remember { mutableStateOf(false) }

    // Result Screen State
    var showResultScreen by remember { mutableStateOf(false) }
    var resultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var resultOriginalSizes by remember { mutableStateOf<List<Long>>(emptyList()) }

    fun updateFileInfo(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            val uri = uris[0]
            fileName = getFileName(context, uri)
            originalSizeBytes = getFileSize(context, uri)
            fileFormat = context.contentResolver.getType(uri)?.split("/")?.lastOrNull()?.uppercase() ?: "???"
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            selectedUris = listOf(initialUri)
            updateFileInfo(selectedUris)
            onConsumeUri()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (it.isNotEmpty()) {
            selectedUris = it
            updateFileInfo(it)
            results = emptyList()
            showResultScreen = false
        }
    }

    if (showResultScreen && resultFiles.isNotEmpty()) {
        CompressionResultScreen(
            context = context,
            originalSizes = resultOriginalSizes,
            compressedFiles = resultFiles,
            vibrations = vibrations,
            onDone = {
                showResultScreen = false
                selectedUris = emptyList()
                results = emptyList()
            },
            onReturnHome = { }
        )
    } else {
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Photo Compression", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                onClick = { pickerLauncher.launch("image/*") }
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (selectedUris.isEmpty()) Icons.Rounded.AddAPhoto else Icons.Rounded.CheckCircle, null, modifier = Modifier.size(48.dp), tint = if (selectedUris.isEmpty()) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50))
                        Text(if (selectedUris.isEmpty()) "Select Photos" else if (selectedUris.size == 1) "Photo Selected" else "${selectedUris.size} Photos Selected")
                    }
                    if (selectedUris.isNotEmpty()) {
                        IconButton(
                            onClick = { selectedUris = emptyList(); results = emptyList() },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (selectedUris.isNotEmpty()) {
                // File info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Size: ${formatSize(originalSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("Format: $fileFormat", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = compressionMode == "Size",
                        onClick = { compressionMode = "Size" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("Target Size") }
                    SegmentedButton(
                        selected = compressionMode == "Percent",
                        onClick = { compressionMode = "Percent" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Percentage") }
                }

                if (compressionMode == "Size") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val originalMb = originalSizeBytes / (1024f * 1024f)
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Target: ", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = if (targetMb == 0f) "" else String.format(Locale.getDefault(), "%.1f", targetMb),
                                onValueChange = { 
                                    val value = it.toFloatOrNull() ?: 0f
                                    targetMb = value.coerceAtMost(originalMb)
                                },
                                modifier = Modifier.width(100.dp),
                                suffix = { Text("MB") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            Spacer(Modifier.weight(1f))
                            Text("Limit: ${String.format(Locale.getDefault(), "%.1f", originalMb)} MB", style = MaterialTheme.typography.bodySmall)
                        }

                        Slider(
                            value = targetMb, 
                            onValueChange = { 
                                targetMb = it.coerceAtMost(originalMb.coerceAtLeast(0.1f))
                                if (vibrations) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }, 
                            valueRange = 0.1f..originalMb.coerceAtLeast(10f)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Compression: ", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = targetPercent.toInt().toString(),
                                onValueChange = { 
                                    val value = it.filter { char -> char.isDigit() }.toFloatOrNull() ?: 0f
                                    targetPercent = value.coerceIn(1f, 100f)
                                },
                                modifier = Modifier.width(100.dp),
                                suffix = { Text("%") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }

                        Slider(
                            value = targetPercent,
                            onValueChange = { 
                                targetPercent = it
                                if (vibrations) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            valueRange = 5f..95f
                        )
                        val estimatedMb = (originalSizeBytes * (targetPercent / 100f)) / (1024f * 1024f)
                        Text("Estimated result: â‰ ${String.format(Locale.getDefault(), "%.2f", estimatedMb)} MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        OptionRow("Remove Metadata", removeMetadata, { removeMetadata = it }, Icons.Rounded.Info)
                    }
                }

                if (isProcessing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        val label = if (selectedUris.size > 1) "Processing batch..." else "Processing..."
                        Text(label)
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            isProcessing = true
                            scope.launch(Dispatchers.IO) {
                                val res = mutableListOf<File>()
                                val originalSizes = mutableListOf<Long>()
                                selectedUris.forEach { uri ->
                                    try {
                                        val size = getFileSize(context, uri)
                                        val finalTargetMb = if (compressionMode == "Percent") {
                                            (size * (targetPercent / 100f)) / (1024f * 1024f)
                                        } else {
                                            targetMb
                                        }
                                        val f = compressPhoto(context, uri, finalTargetMb)
                                        res.add(f)
                                        originalSizes.add(size)
                                        onStatsUpdate(f.name, size, f.length(), "photo", f.absolutePath)
                                        if (autoSave && saveUri != null) {
                                            autoSaveToFile(context, f, saveUri, f.name, "image/jpeg")
                                        }
                                    } catch (_: Exception) {}
                                }
                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                    results = res
                                    if (res.isNotEmpty()) {
                                        showCompletionNotification(context, if (res.size > 1) "batch photo" else res[0].name)
                                        resultFiles = res
                                        resultOriginalSizes = originalSizes
                                        showResultScreen = true
                                    } else {
                                        Toast.makeText(context, "Photo compression failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (selectedUris.size > 1) "Start Batch Compression" else "Start Compression")
                    }
                }
            }
        }
    }
}

