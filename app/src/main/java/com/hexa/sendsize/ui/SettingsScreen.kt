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
fun SettingsTab(
    autoSave: Boolean, onAutoSaveChange: (Boolean) -> Unit,
    saveUri: String?, onSaveUriChange: (String?) -> Unit,
    vibrations: Boolean, onVibrationsChange: (Boolean) -> Unit,
    highQualityMode: Boolean, onQualityChange: (Boolean) -> Unit,
    compressionPriority: String, onPriorityChange: (String) -> Unit,
    technicalLogs: Boolean, onTechnicalLogsChange: (Boolean) -> Unit,
    totalFiles: Int, totalBytesSaved: Long
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            onSaveUriChange(it.toString())
        }
    }

    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 4 },
            exit = fadeOut(tween(120))
        ) {
            AboutPage(totalFiles, totalBytesSaved) { showAbout = false }
        }
    } else {
        Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("System Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingRow("Vibrations", vibrations, onVibrationsChange, Icons.Rounded.Vibration)
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    
                    Column {
                        SettingRow("High Quality Mode", highQualityMode, onQualityChange, Icons.Rounded.HighQuality)
                        Text(
                            "Uses advanced encoding for better visual results at the cost of processing time.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Compress, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Compression Priority", style = MaterialTheme.typography.bodyLarge)
                        }
                        
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = compressionPriority == "Exact",
                                onClick = { onPriorityChange("Exact") },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text("Exact Size") }
                            SegmentedButton(
                                selected = compressionPriority == "Quality",
                                onClick = { onPriorityChange("Quality") },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text("Quality") }
                        }
                        
                        Text(
                            if (compressionPriority == "Exact") 
                                "Prioritize reaching the selected file size as closely as possible. Best for upload limits."
                            else 
                                "Prioritize quality and encoding speed. Output size may differ from the selected target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    
                    Column {
                        SettingRow("Technical Logs", technicalLogs, onTechnicalLogsChange, Icons.Rounded.Terminal)
                        Text(
                            "Display detailed compression and conversion logs while files are being processed.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingRow("Auto-Save", autoSave, onAutoSaveChange, Icons.Rounded.Save)

                    if (autoSave) {
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (saveUri == null) "Select Save Folder" else "Change Folder")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                onClick = { showAbout = true }
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null)
                    Spacer(Modifier.width(12.dp))
                    Text("About SendSize", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
        }
    }
}

@Composable
fun AboutPage(totalFiles: Int, totalBytesSaved: Long, onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Icon(
            Icons.Rounded.AutoFixHigh,
            null,
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .padding(24.dp),
            tint = MaterialTheme.colorScheme.primary)

        Text("SendSize", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text("Version 1.1", style = MaterialTheme.typography.bodyMedium)
        Text("GNU GPL v3", style = MaterialTheme.typography.bodySmall)

        OutlinedButton(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hexa386/SendSize/"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to open repository", Toast.LENGTH_SHORT).show()
                }
            },
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Rounded.Code, null)
            Spacer(Modifier.width(8.dp))
            Text("GitHub Repository")
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your Statistics", fontWeight = FontWeight.Bold)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(Modifier.weight(1f), "Files", totalFiles.toString(), Icons.Rounded.Description, MaterialTheme.colorScheme.secondaryContainer)
                    StatCard(Modifier.weight(1f), "Saved", formatSize(totalBytesSaved), Icons.Rounded.CloudDone, MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
        }
    }
}


