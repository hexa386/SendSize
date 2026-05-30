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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConvertTab(autoSave: Boolean, saveUri: String?, vibrations: Boolean, initialUri: Uri?, onConsumeUri: () -> Unit, onStatsUpdate: (String, Long, Long, String, String?) -> Unit, technicalLogsEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            selectedUri = initialUri
            onConsumeUri()
        }
    }

    var selectedType by remember { mutableStateOf("Video") }
    
    val videoFormats = listOf("MP4", "MKV", "MOV", "WEBM", "AVI")
    val imageFormats = listOf("JPEG", "PNG", "WEBP", "ICO", "GIF")
    val audioFormats = listOf("MP3", "M4A", "WAV", "FLAC", "AAC", "OGG")
    
    var selectedFormat by remember { mutableStateOf("MP4") }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var extractAudio by remember { mutableStateOf(false) }
    var showResultScreen by remember { mutableStateOf(false) }
    var originalFormat by remember { mutableStateOf("") }
    var technicalLogs by remember { mutableStateOf("") }

    LaunchedEffect(selectedUri) {
        selectedUri?.let { uri ->
            val mime = context.contentResolver.getType(uri)
            originalFormat = mime?.split("/")?.lastOrNull()?.uppercase() ?: "???"
            if (mime?.startsWith("image") == true) {
                selectedType = "Image"
                selectedFormat = "JPEG"
            } else if (mime?.startsWith("audio") == true) {
                selectedType = "Audio"
                selectedFormat = "MP3"
            } else {
                selectedType = "Video"
                selectedFormat = "MP4"
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            resultFile = null
            showResultScreen = false
        }
    }

    if (showResultScreen && resultFile != null) {
        ConversionResultScreen(
            context = context,
            originalFormat = originalFormat,
            targetFormat = selectedFormat,
            resultFile = resultFile!!,
            onDone = {
                showResultScreen = false
                selectedUri = null
                resultFile = null
            }
        )
    } else {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Media Converter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                onClick = { pickerLauncher.launch("*/*") }
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (selectedUri != null) Icons.Rounded.FileDownloadDone else Icons.Rounded.FilePresent, null, modifier = Modifier.size(48.dp), tint = if (selectedUri != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                        Text(if (selectedUri != null) "File Selected" else "Tap to Select File")
                        if (selectedUri != null) {
                            Text(selectedUri?.path?.split("/")?.lastOrNull() ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    if (selectedUri != null) {
                        IconButton(
                            onClick = { selectedUri = null; resultFile = null },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (selectedUri != null) {
                Text("Detected Type: $selectedType", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                if (selectedType == "Video") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Extract Audio Only")
                        Spacer(Modifier.weight(1f))
                        Switch(extractAudio, { extractAudio = it })
                    }
                }

                Text("Target Format", fontWeight = FontWeight.Bold)
                val currentFormats = when(selectedType) {
                    "Video" -> if (extractAudio) audioFormats else videoFormats
                    "Image" -> imageFormats
                    else -> audioFormats
                }
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    currentFormats.forEach { format ->
                        FilterChip(
                            modifier = Modifier.padding(4.dp),
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = { Text(format, modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center) }
                        )
                    }
                }

                if (isProcessing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Converting...", style = MaterialTheme.typography.bodyMedium)
                        
                        if (technicalLogsEnabled) {
                            Spacer(Modifier.height(8.dp))
                            LogPanel(technicalLogs)
                        }
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            isProcessing = true
                            progress = 0f
                            val originalSize = getFileSize(context, selectedUri!!)
                            
                            if (selectedType == "Image") {
                                scope.launch {
                                    try {
                                        val file = convertImage(context, selectedUri!!, selectedFormat)
                                        isProcessing = false
                                        resultFile = file
                                        onStatsUpdate(file.name, originalSize, file.length(), "convert", file.absolutePath)
                                        if (autoSave && saveUri != null) {
                                            autoSaveToFile(context, file, saveUri, file.name, context.contentResolver.getType(file.toUri()) ?: "*/*")
                                        }
                                        showCompletionNotification(context, "image")
                                        showResultScreen = true
                                    } catch (e: Exception) {
                                        isProcessing = false
                                        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                startConversion(
                                    context, selectedUri!!, selectedFormat, extractAudio || selectedType == "Audio",
                                    { progress = it },
                                    { file ->
                                        isProcessing = false
                                        resultFile = file
                                        onStatsUpdate(file.name, originalSize, file.length(), "convert", file.absolutePath)
                                        if (autoSave && saveUri != null) {
                                            autoSaveToFile(context, file, saveUri, file.name, if (extractAudio || selectedType == "Audio") "audio/mpeg" else "video/mp4")
                                        }
                                        showCompletionNotification(context, "conversion")
                                        showResultScreen = true
                                    },
                                    { isProcessing = false; Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                                    { technicalLogs += it + "\n" }
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Transform, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Conversion")
                    }
                }
            }
        }
    }
}
