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
fun ConversionResultScreen(
    context: Context,
    originalFormat: String,
    targetFormat: String,
    resultFile: File,
    onDone: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) } // 0: scramble, 1: success, 2: UI
    var scrambledText by remember { mutableStateOf(originalFormat) }
    
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { saveFileToUri(context, resultFile, it) }
    }

    LaunchedEffect(Unit) {
        // Scramble animation
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(15) {
            scrambledText = (1..targetFormat.length).map { chars.random() }.joinToString("")
            delay(60)
        }
        scrambledText = targetFormat
        delay(200)
        step = 1
        delay(1000)
        step = 2
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            AnimatedContent(targetState = step, label = "ConvStep") { s ->
                when (s) {
                    0 -> {
                        Text(
                            scrambledText,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    1 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(120.dp), tint = Color(0xFF4CAF50))
                            Text("Converted!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                    }
                    2 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Conversion Complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            ) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ResultRow("Format", "$originalFormat â†’ $targetFormat")
                                    ResultRow("Final Size", formatSize(resultFile.length()))
                                }
                            }

                            Button(
                                onClick = { saveLauncher.launch(resultFile.name) },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.Save, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Save to Storage")
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { openFile(context, resultFile) },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.OpenInNew, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Open")
                                }
                                OutlinedButton(
                                    onClick = { shareFile(context, resultFile) },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.Share, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Share")
                                }
                            }

                            TextButton(onClick = onDone) { Text("Return to Main Menu") }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun CompressionResultScreen(
    context: Context,
    originalSizes: List<Long>,
    compressedFiles: List<File>,
    vibrations: Boolean,
    onDone: () -> Unit,
    onReturnHome: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var step by remember { mutableIntStateOf(0) } // 0: countdown, 1: success check, 2: final UI
    
    val totalOriginal = originalSizes.sum()
    val totalCompressed = compressedFiles.sumOf { it.length() }
    
    var animatedValue by remember { mutableLongStateOf(totalOriginal) }
    
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { saveFileToUri(context, compressedFiles[0], it) }
    }

    LaunchedEffect(Unit) {
        // Step 0: Countdown
        val duration = 1200L
        val startTime = System.currentTimeMillis()
        var lastTickValue = totalOriginal
        
        while (System.currentTimeMillis() - startTime < duration) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / duration).coerceAtMost(1f)
            // Use an easing function for smoother countdown
            val easedProgress = 1f - (1f - progress) * (1f - progress)
            animatedValue = totalOriginal - ((totalOriginal - totalCompressed) * easedProgress).toLong()
            
            // Haptic ticks for a "digital counter" feel
            val totalDiff = totalOriginal - totalCompressed
            if (vibrations && (lastTickValue - animatedValue) > (totalDiff / 25)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastTickValue = animatedValue
            }
            
            delay(16)
        }
        animatedValue = totalCompressed
        delay(200)
        step = 1
        if (vibrations) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        
        // Step 1: Success Check
        delay(1000)
        step = 2
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            AnimatedContent(targetState = step, label = "ResultStep") { s ->
                when (s) {
                    0 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Compressing...",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                formatSize(animatedValue),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    1 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(120.dp).scale(1.2f),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Success!",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    2 -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Processing Complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            ) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ResultRow("Original Size", formatSize(totalOriginal))
                                    ResultRow("Final Size", formatSize(totalCompressed))
                                    val saved = (totalOriginal - totalCompressed).coerceAtLeast(0)
                                    ResultRow("Space Saved", formatSize(saved), color = Color(0xFF4CAF50))
                                    val percent = if (totalOriginal > 0) (saved * 100 / totalOriginal) else 0
                                    ResultRow("Efficiency", "$percent%", color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            if (compressedFiles.size == 1) {
                                Button(
                                    onClick = { saveLauncher.launch(compressedFiles[0].name) },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.Save, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save to Storage")
                                }
                                
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { openFile(context, compressedFiles[0]) },
                                        modifier = Modifier.weight(1f).height(56.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Rounded.OpenInNew, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Open")
                                    }
                                    OutlinedButton(
                                        onClick = { shareFile(context, compressedFiles[0]) },
                                        modifier = Modifier.weight(1f).height(56.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(Icons.Rounded.Share, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Share")
                                    }
                                }
                            } else {
                                Text("${compressedFiles.size} files processed successfully")
                            }

                            TextButton(
                                onClick = onDone,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Return to Main Menu")
                            }
                        }
                    }
                }
            }
        }
    }
}


