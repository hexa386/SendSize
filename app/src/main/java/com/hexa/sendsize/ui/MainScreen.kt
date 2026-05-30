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
fun AnimatedAmbientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val color1 = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    val color2 = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f)
    val color3 = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.1f)

    val bgColor = MaterialTheme.colorScheme.background

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        val x1 = width * (0.5f + 0.2f * sin(phase.toDouble()).toFloat())
        val y1 = height * (0.5f + 0.2f * sin(phase.toDouble() + 1.0).toFloat())
        
        val x2 = width * (0.5f + 0.2f * sin(phase.toDouble() + 2.0).toFloat())
        val y2 = height * (0.5f + 0.2f * sin(phase.toDouble() + 3.0).toFloat())

        drawRect(color = bgColor)
        
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color1, Color.Transparent),
                center = Offset(x1, y1),
                radius = width * 1.5f
            )
        )
        
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color2, Color.Transparent),
                center = Offset(width - x2, height - y2),
                radius = width * 1.2f
            )
        )

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color3, Color.Transparent),
                center = Offset(x2, height - y1),
                radius = width * 1.0f
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    
    var setupComplete by remember { mutableStateOf(prefs.getBoolean("setup_complete", false)) }
    
    if (!setupComplete) {
        SetupWizard {
            prefs.edit().putBoolean("setup_complete", true).apply()
            setupComplete = true
        }
    } else {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        
        var autoSaveEnabled by remember { mutableStateOf(prefs.getBoolean("auto_save", false)) }
        var autoSaveUri by remember { mutableStateOf(prefs.getString("auto_save_uri", null)) }
        var vibrationsEnabled by remember { mutableStateOf(prefs.getBoolean("vibrations", true)) }
        var highQualityMode by remember { mutableStateOf(prefs.getBoolean("high_quality", true)) }
        var compressionPriority by remember { mutableStateOf(prefs.getString("compression_priority", "Exact") ?: "Exact") }
        var technicalLogsEnabled by remember { mutableStateOf(prefs.getBoolean("technical_logs", false)) }
        var showSettingsSheet by remember { mutableStateOf(false) }

        var initialUri by remember { mutableStateOf<Uri?>(null) }
        var showHistory by remember { mutableStateOf(false) }

        // Stats
        var totalFiles by remember { mutableIntStateOf(prefs.getInt("total_files", 0)) }
        var totalBytesSaved by remember { mutableLongStateOf(prefs.getLong("total_bytes_saved", 0L)) }
        var historyJson by remember { mutableStateOf(prefs.getString("history", "[]") ?: "[]") }
        val history = remember(historyJson) {
            try { Json.decodeFromString<List<HistoryItem>>(historyJson) } catch (e: Exception) { emptyList() }
        }

        fun addToHistory(item: HistoryItem) {
            val newHistory = (listOf(item) + history).take(50)
            historyJson = Json.encodeToString(newHistory)
            prefs.edit().putString("history", historyJson).apply()
        }

        fun updateStats(fileName: String, originalSize: Long, compressedSize: Long, type: String, filePath: String? = null) {
            val saved = (originalSize - compressedSize).coerceAtLeast(0)
            totalFiles++
            totalBytesSaved += saved
            prefs.edit()
                .putInt("total_files", totalFiles)
                .putLong("total_bytes_saved", totalBytesSaved)
                .apply()
            
            addToHistory(HistoryItem(fileName, System.currentTimeMillis(), originalSize, compressedSize, type, filePath))
        }

        LaunchedEffect(autoSaveEnabled) { prefs.edit().putBoolean("auto_save", autoSaveEnabled).apply() }
        LaunchedEffect(autoSaveUri) { prefs.edit().putString("auto_save_uri", autoSaveUri).apply() }
        LaunchedEffect(vibrationsEnabled) { prefs.edit().putBoolean("vibrations", vibrationsEnabled).apply() }
        LaunchedEffect(highQualityMode) { prefs.edit().putBoolean("high_quality", highQualityMode).apply() }
        LaunchedEffect(compressionPriority) { prefs.edit().putString("compression_priority", compressionPriority).apply() }
        LaunchedEffect(technicalLogsEnabled) { prefs.edit().putBoolean("technical_logs", technicalLogsEnabled).apply() }

        val tabs = listOf("Home", "Video", "Photo", "Convert")
        val icons = listOf(
            Icons.Rounded.Home,
            Icons.Rounded.VideoLibrary,
            Icons.Rounded.PhotoLibrary,
            Icons.Rounded.Transform
        )

        BackHandler(enabled = selectedTab != 0 || showHistory) {
            if (showHistory) showHistory = false
            else selectedTab = 0
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "SendSize",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            )
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = title) },
                            label = { Text(title) },
                            selected = selectedTab == index,
                            onClick = { selectedTab = index; showHistory = false }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedAmbientBackground()
                
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    AnimatedContent(
                        targetState = if (showHistory) -1 else selectedTab,
                        transitionSpec = {
                            val slideSpec = spring<IntOffset>(stiffness = Spring.StiffnessLow)
                            val fadeSpec = spring<Float>(stiffness = Spring.StiffnessLow)
                            slideInHorizontally(slideSpec) { if (targetState > initialState) it else -it } + fadeIn(fadeSpec) togetherWith
                                    slideOutHorizontally(slideSpec) { if (targetState > initialState) -it else it } + fadeOut(fadeSpec)
                        },
                        label = "TabTransition"
                    ) { targetState ->
                        when (targetState) {
                            -1 -> HistoryTab(history) { showHistory = false }
                            0 -> HomeTab(onShowHistory = { showHistory = true }) { index, uri ->
                                initialUri = uri
                                selectedTab = index
                            }
                            1 -> VideoCompressorTab(autoSaveEnabled, autoSaveUri, vibrationsEnabled, initialUri, { initialUri = null }, ::updateStats, compressionPriority, technicalLogsEnabled) { 
                                initialUri = it.toUri()
                                selectedTab = 3
                            }
                            2 -> PhotoCompressorTab(autoSaveEnabled, autoSaveUri, vibrationsEnabled, initialUri, { initialUri = null }, ::updateStats) {
                                initialUri = it.toUri()
                                selectedTab = 3
                            }
                            3 -> ConvertTab(autoSaveEnabled, autoSaveUri, vibrationsEnabled, initialUri, { initialUri = null }, ::updateStats, technicalLogsEnabled)
                        }
                    }
                }

                if (showSettingsSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSettingsSheet = false },
                        dragHandle = { BottomSheetDefaults.DragHandle() },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        SettingsTab(
                            autoSaveEnabled, { autoSaveEnabled = it },
                            autoSaveUri, { autoSaveUri = it },
                            vibrationsEnabled, { vibrationsEnabled = it },
                            highQualityMode, { highQualityMode = it },
                            compressionPriority, { compressionPriority = it },
                            technicalLogsEnabled, { technicalLogsEnabled = it },
                            totalFiles, totalBytesSaved
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SetupWizard(onComplete: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = 1 }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("auto_save_uri", it.toString()).apply()
            onComplete()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Welcome to SendSize!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            
            Text(
                if (step == 0) "To get started, we need permission to send you notifications when tasks are complete."
                else "Lastly, please select a default folder where your compressed files will be saved.",
                textAlign = TextAlign.Center
            )

            Button(
                onClick = {
                    if (step == 0) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            step = 1
                        }
                    } else {
                        folderPicker.launch(null)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (step == 0) "Grant Permission" else "Select Folder")
            }
        }
    }
}

@Composable
fun HomeTab(onShowHistory: () -> Unit, onNavigate: (Int, Uri) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val type = context.contentResolver.getType(it)
            if (type?.startsWith("video") == true) onNavigate(1, it)
            else if (type?.startsWith("image") == true) onNavigate(2, it)
            else onNavigate(3, it)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .clickable { picker.launch("*/*") },
            shape = CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Add, 
                        null, 
                        modifier = Modifier.size(64.dp), 
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Smart Pick", 
                        style = MaterialTheme.typography.titleLarge, 
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Tap to select file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onShowHistory) {
            Icon(Icons.Rounded.History, null)
            Spacer(Modifier.width(8.dp))
            Text("View History")
        }
    }
}


