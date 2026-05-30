package com.hexa.sendsize

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
import com.hexa.sendsize.ui.theme.SendSizeTheme
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

@Serializable
data class HistoryItem(
    val fileName: String,
    val timestamp: Long,
    val originalSize: Long,
    val compressedSize: Long,
    val type: String,
    val filePath: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        
        setContent {
            SendSizeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

private fun createNotificationChannel(context: Context) {
    val name = "Compression Updates"
    val descriptionText = "Notifications for finished compressions"
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val channel = NotificationChannel("SENDSIZE_CHANNEL", name, importance).apply {
        description = descriptionText
    }
    val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)
}

fun showCompletionNotification(context: Context, fileName: String) {
    val builder = NotificationCompat.Builder(context, "SENDSIZE_CHANNEL")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Task Complete")
        .setContentText("Finished processing $fileName")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
}

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
                        if (selectedTab < 4) {
                            IconButton(onClick = { selectedTab = 4; showHistory = false }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (selectedTab < 4) {
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
                            4 -> SettingsTab(
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

@Composable
fun HistoryTab(history: List<HistoryItem>, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Text("Compression History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = when(item.type) {
                                "video" -> Icons.Rounded.Movie
                                "photo" -> Icons.Rounded.Image
                                else -> Icons.Rounded.Transform
                            }
                            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(formatSize(item.compressedSize), style = MaterialTheme.typography.bodySmall)
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { 
                                    item.filePath?.let { openFile(context, File(it)) } 
                                }) {
                                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { 
                                    item.filePath?.let { shareFile(context, File(it)) }
                                }) {
                                    Icon(Icons.Rounded.Share, null, modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    "-${formatSize(item.originalSize - item.compressedSize)}",
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Black,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(Modifier.padding(20.dp)) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
                                    ResultRow("Format", "$originalFormat → $targetFormat")
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
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fileName = getFileName(context, uri)
                originalSizeBytes = getFileSize(context, uri)
                fileFormat = context.contentResolver.getType(uri)?.split("/")?.lastOrNull()?.uppercase() ?: "???"
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                durationS = String.format(Locale.getDefault(), "%d:%02d", dur / 1000 / 60, (dur / 1000) % 60)
            } catch (e: Exception) {
                android.util.Log.e("SendSize", "Error getting file info", e)
                fileName = "Unknown"
            } finally {
                retriever.release()
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
                // Error Screen
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
                        Icon(Icons.Rounded.Error, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Compression Failed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(
                            onClick = { errorMessage = null },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Go Back")
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
                                        "Estimated result: ≈ ${String.format(Locale.getDefault(), "%.1f", estimatedMb)} MB",
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
                                onError = {
                                    isProcessing = false
                                    errorMessage = it
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
                progress = { progress },
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
                Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
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
    onError: (String) -> Unit,
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
                onError = { error ->
                    android.util.Log.e("SendSize", "Error on file ${currentIndex + 1}: $error")
                    onLog("ERROR on file ${currentIndex + 1}: $error")
                    // Stop batch on error and notify UI
                    onError(error)
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

fun getFileName(context: Context, uri: Uri): String {
    var name = ""
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i != -1 && cursor.moveToFirst()) name = cursor.getString(i)
    }
    return name.ifEmpty { uri.path?.split("/")?.lastOrNull() ?: "Unknown" }
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

@Composable
fun ResultRow(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share File"))
}

fun openFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
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
                        Text("Estimated result: ≈ ${String.format(Locale.getDefault(), "%.2f", estimatedMb)} MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                                    showCompletionNotification(context, if (res.size > 1) "batch photo" else res[0].name)
                                    resultFiles = res
                                    resultOriginalSizes = originalSizes
                                    showResultScreen = true
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

private suspend fun convertImage(context: Context, uri: Uri, targetFormat: String): File = withContext(Dispatchers.IO) {
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

@Composable
fun LogPanel(logs: String) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    LaunchedEffect(logs) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f))
    ) {
        Box(Modifier.padding(8.dp)) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxWidth()
            ) {
                Text(
                    text = logs,
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
            }
            
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Technical Logs", logs)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
            ) {
                Icon(Icons.Rounded.ContentCopy, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

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
        AboutPage(totalFiles, totalBytesSaved) { showAbout = false }
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
        Text("Version 1.0.0", style = MaterialTheme.typography.bodyMedium)
        Text("Created by Hexa", style = MaterialTheme.typography.bodySmall)

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

@Composable
fun SettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector) {
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f))
        Switch(checked, { onCheckedChange(it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
    }
}

@Composable
fun OptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun startVideoCompression(
    context: Context, uri: Uri, targetMb: Float, removeAudio: Boolean = false,
    compressionPriority: String = "Exact",
    onProgress: (Float) -> Unit,
    onStatusUpdate: (String) -> Unit = {},
    onComplete: (File) -> Unit,
    onError: (String) -> Unit,
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
                onError("Could not read input file: ${e.message}")
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
                onError("Invalid video duration: $durationMs ms")
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
                            onError("Output file was not created successfully")
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
                            onError("Could not reach target size. Final output was ${formatSize(actualSize)}, target was ${formatSize(targetSizeBytes)}.")
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
                        onError("Encoding error: ${exception.message}")
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
                onError("Could not start compression: ${e.message}")
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
            onError("Initialization error: ${e.message}")
        }
    }
    
    runAttempt(1)
}

private suspend fun compressPhoto(context: Context, uri: Uri, targetMb: Float): File = withContext(Dispatchers.IO) {
    val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Fail")
    val original = BitmapFactory.decodeStream(inputStream)
    inputStream.close()
    val targetBytes = (targetMb * 1024 * 1024 * 0.9).toLong()
    var quality = 90
    var lastFile: File? = null
    while (quality > 5) {
        val out = ByteArrayOutputStream()
        original.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= targetBytes || quality <= 10) {
            val f = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { it.write(out.toByteArray()) }
            lastFile = f
            break
        }
        quality -= 10
    }
    lastFile ?: throw Exception("Fail")
}

private fun getFileSize(context: Context, uri: Uri): Long {
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val i = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (i != -1 && cursor.moveToFirst()) size = cursor.getLong(i)
    }
    return size
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
}

private fun saveFileToUri(context: Context, source: File, destination: Uri) {
    try {
        context.contentResolver.openOutputStream(destination)?.use { source.inputStream().copyTo(it) }
        Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show() }
}

fun autoSaveToFile(context: Context, source: File, treeUri: String, fileName: String, mimeType: String) {
    try {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        val file = root?.createFile(mimeType, fileName)
        file?.uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> source.inputStream().copyTo(out) } }
    } catch (_: Exception) {}
}
