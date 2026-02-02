package com.smarthome.controller.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.smarthome.controller.data.ConnectionMode
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.data.SystemStatus
import com.smarthome.controller.service.SmsMonitorService
import com.smarthome.controller.utils.MqttManager
import com.smarthome.controller.utils.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// --- PREMIUM PALETTE ---
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumAccent = Color(0xFF3B82F6)
private val PremiumDanger = Color(0xFFEF4444)
private val PremiumSuccess = Color(0xFF10B981)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)
private val PremiumDisabled = Color(0xFF475569)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToVideo: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    
    val systemStatus by SystemState.currentStatusFlow.collectAsState()
    val mqttConnectionState by MqttManager.connectionState.collectAsState()

    LaunchedEffect(Unit) {
        SystemState.init(context)
        if (SystemState.connectionMode != ConnectionMode.SMS_ONLY) {
            MqttManager.startAutoConnect(context)
        }
    }

    val permissions = mutableListOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionsState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            val serviceIntent = Intent(context, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    Scaffold(
        containerColor = PremiumBg,
        contentColor = PremiumText
    ) { padding ->
        if (permissionsState.allPermissionsGranted) {
            PremiumDashboard(
                status = systemStatus,
                mqttState = mqttConnectionState,
                onSettingsClick = onNavigateToSettings,
                onVideoClick = onNavigateToVideo,
                onHistoryClick = onNavigateToHistory,
                modifier = Modifier.padding(padding)
            )
        } else {
            PermissionRequestScreen(onRequest = {
                permissionsState.launchMultiplePermissionRequest()
            })
        }
    }
}


@Composable
fun PremiumDashboard(
    status: SystemStatus,
    mqttState: MqttManager.ConnectionState,
    onSettingsClick: () -> Unit,
    onVideoClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sendingCommand by remember { mutableStateOf<String?>(null) }
    
    val isSystemOffline = (!mqttState.isConnected || status.lastUpdate <= 0L) && sendingCommand == null

    val sendCommand: (String) -> Unit = { command ->
        sendingCommand = command
        
        if (command == "DISARM") SystemState.clearAlarm()
        
        when (SystemState.connectionMode) {
            ConnectionMode.SMS_ONLY -> {
                SmsSender.sendCommand(context, command)
                scope.launch {
                    delay(500)
                    sendingCommand = null
                }
            }
            ConnectionMode.MQTT_ONLY -> {
                scope.launch(Dispatchers.IO) {
                    MqttManager.sendCommand(context, command)
                    delay(500)
                    sendingCommand = null
                }
            }
            ConnectionMode.HYBRID -> {
                SmsSender.sendCommand(context, command)
                scope.launch(Dispatchers.IO) {
                    MqttManager.sendCommand(context, command)
                    delay(500)
                    sendingCommand = null
                }
            }
        }
    }

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        
        // 1. SCROLLABLE CONTENT
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp), 
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Добро пожаловать", fontSize = 14.sp, color = PremiumTextSec)
                    Text("Мой Дом", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(
                        onClick = onHistoryClick,
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(Icons.Rounded.History, null, tint = PremiumAccent)
                    }
                    IconButton(
                        onClick = { sendCommand("STATUS") },
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = PremiumText)
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(Icons.Rounded.Settings, null, tint = PremiumTextSec)
                    }
                }
            }

            // OFFLINE INDICATOR
            AnimatedVisibility(visible = sendingCommand != null || !mqttState.isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (mqttState.isConnected) PremiumAccent.copy(0.2f) else PremiumDanger.copy(0.2f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sendingCommand != null) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PremiumAccent)
                        Spacer(Modifier.width(8.dp))
                        Text("Отправка $sendingCommand...", fontSize = 14.sp, color = PremiumAccent)
                    } else {
                        Icon(Icons.Rounded.WifiOff, null, modifier = Modifier.size(16.dp), tint = PremiumDanger)
                        Spacer(Modifier.width(8.dp))
                        Text(mqttState.statusText, fontSize = 14.sp, color = PremiumDanger)
                    }
                }
            }

            // MAIN VISUAL CONTENT
            Box {
                Column(
                    modifier = Modifier.alpha(if (isSystemOffline) 0.6f else 1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusHeroCard(status, isOffline = isSystemOffline)

                    VideoPreviewCard(
                        onClick = onVideoClick,
                        mqttConnected = mqttState.isConnected,
                        status = status,
                        isOffline = isSystemOffline
                    )

                    // SENSORS
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "ДАТЧИКИ И СОСТОЯНИЕ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PremiumTextSec
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SensorCard(
                                    title = "Движение",
                                    value = if (isSystemOffline) "-" 
                                            else if (status.pirStatus == "MOTION") "Обнаружено" 
                                            else "Отсутствует",
                                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                                    color = if (isSystemOffline) PremiumDisabled 
                                            else if (status.pirStatus == "MOTION") PremiumDanger else PremiumSuccess
                                )
                                SensorCard(
                                    title = "Уровень звука",
                                    value = if (isSystemOffline) "-" else "${status.soundLevel} dB",
                                    icon = Icons.Rounded.GraphicEq,
                                    color = if (isSystemOffline) PremiumDisabled else PremiumAccent
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SensorCard(
                                    title = "Система",
                                    value = if (status.lastUpdate > 0) "В сети" else "Не в сети",
                                    icon = if (status.lastUpdate > 0) Icons.Rounded.Cloud else Icons.Rounded.CloudOff,
                                    color = if (status.lastUpdate > 0) PremiumSuccess else PremiumDanger
                                )
                                SensorCard(
                                    title = "Связь",
                                    value = when (SystemState.connectionMode) {
                                        ConnectionMode.MQTT_ONLY -> if (mqttState.isConnected) "MQTT ✓" else "MQTT ✗"
                                        ConnectionMode.SMS_ONLY -> "SMS"
                                        ConnectionMode.HYBRID -> if (mqttState.isConnected) "Гибрид ✓" else "SMS"
                                    },
                                    icon = if (mqttState.isConnected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                                    color = if (mqttState.isConnected) PremiumSuccess else PremiumTextSec
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (status.lastUpdate > 0) {
                            val timeDiff = (currentTime - status.lastUpdate) / 1000
                            when {
                                timeDiff < 60 -> "Обновлено: только что"
                                timeDiff < 3600 -> "Обновлено: ${timeDiff / 60} мин назад"
                                else -> "Обновлено: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.lastUpdate))}"
                            }
                        } else {
                            "Ожидание данных..."
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        color = PremiumTextSec.copy(0.5f)
                    )
                }

                if (isSystemOffline) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { }
                    )
                }
            }
        }

        // 2. FIXED BOTTOM CONTROL
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            PremiumBg.copy(alpha = 0.95f),
                            PremiumBg
                        ),
                        startY = 0f
                    )
                )
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
             SlideActionControl(
                isArmed = status.alarmEnabled,
                isEnabled = !isSystemOffline,
                onSlide = { shouldArm ->
                    sendCommand(if (shouldArm) "ARM" else "DISARM")
                }
            )
        }
    }
}


@Composable
fun StatusHeroCard(status: SystemStatus, isOffline: Boolean = false) {
    val gradient = if (isOffline) {
        Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF1E293B)))
    } else if (status.isAlarm) {
        Brush.linearGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
    } else if (status.alarmEnabled) {
        Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF047857)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF6B7280), Color(0xFF4B5563)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = if (isOffline) "СИСТЕМА НЕДОСТУПНА"
                    else if (status.isAlarm) "ТРЕВОГА!"
                    else if (status.alarmEnabled) "СИСТЕМА ПОД ОХРАНОЙ"
                    else "ОПОВЕЩЕНИЯ ОТКЛЮЧЕНЫ",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isOffline) Color.White.copy(0.5f) else Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isOffline) "Нет связи с контроллером"
                    else if (status.isAlarm) "Обнаружено вторжение"
                    else if (status.alarmEnabled) "Все датчики активны"
                    else "Датчики не уведомляют",
                    fontSize = 13.sp,
                    color = Color.White.copy(if (isOffline) 0.3f else 0.8f)
                )
            }
            Icon(
                imageVector = if (isOffline) Icons.Rounded.CloudOff
                             else if (status.isAlarm) Icons.Rounded.Warning 
                             else if (status.alarmEnabled) Icons.Rounded.Shield
                             else Icons.Rounded.ShieldMoon,
                contentDescription = null,
                modifier = Modifier.size(40.dp).alpha(if (isOffline) 0.3f else 0.8f),
                tint = Color.White
            )
        }
    }
}

@Composable
fun VideoPreviewCard(
    onClick: () -> Unit, 
    mqttConnected: Boolean,
    status: SystemStatus,
    isOffline: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val previewBitmap by MqttManager.latestPreview.collectAsState()
    
    var lastPreviewRequest by remember { mutableStateOf(0L) }
    var isLoadingPreview by remember { mutableStateOf(false) }

    val shouldShowPreview = previewBitmap != null && 
                           mqttConnected && 
                           status.lastUpdate > 0 && 
                           !isOffline
    
    fun requestPreview() {
        if (!mqttConnected || isOffline) return
        
        val now = System.currentTimeMillis()
        if (now - lastPreviewRequest < 3000) return
        
        lastPreviewRequest = now
        isLoadingPreview = true
        
        scope.launch(Dispatchers.IO) {
            MqttManager.sendCameraCommand(context, "STREAM_PREVIEW")
            delay(5000)
            if (isLoadingPreview) {
                isLoadingPreview = false
            }
        }
    }
    
    LaunchedEffect(previewBitmap) {
        if (previewBitmap != null) {
            isLoadingPreview = false
        }
    }

    LaunchedEffect(mqttConnected, status.alarmEnabled, status.lastUpdate) {
        if (mqttConnected && status.alarmEnabled && status.lastUpdate > 0) {
            delay(2000)
            requestPreview()
            
            while (mqttConnected && status.alarmEnabled && status.lastUpdate > 0) {
                delay(15000)
                if (mqttConnected && status.alarmEnabled && status.lastUpdate > 0) {
                    requestPreview()
                }
            }
        }
    }

    // Определяем, нужно ли затемнять экран (если есть текст статуса)
    // "Умное затемнение"
    val needOverlay = (!mqttConnected || isOffline) || (!status.alarmEnabled && !status.streaming) || isLoadingPreview

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.78f)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = mqttConnected && !isOffline) { onClick() }
    ) {
        if (shouldShowPreview) {
            Image(
                bitmap = previewBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(PremiumCard, Color(0xFF1A1F2C))
                        )
                    )
            )

            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    Color.White.copy(0.03f),
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    2f
                )
                drawLine(
                    Color.White.copy(0.03f),
                    Offset(size.width / 2, 0f),
                    Offset(size.width / 2, size.height),
                    2f
                )
            }
        }

        // 🔥 "УМНОЕ" ЗАТЕМНЕНИЕ (OVERLAY)
        // Если нужно показать статус (нет сети, выключено, загрузка), 
        // мы затемняем картинку на 50%, чтобы белый текст читался на любом фоне.
        if (needOverlay) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.5f)) // Затемнение
            )
        } else {
            // Обычный градиент для читаемости нижнего текста
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(0.6f)
                            )
                        )
                    )
            )
        }

        // ЦЕНТРАЛЬНЫЙ БЛОК (Иконки + Текст)
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isOffline || !mqttConnected) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.WifiOff, 
                        null, 
                        tint = Color.White.copy(0.8f), // Чуть ярче
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Нет подключения", 
                        color = Color.White, 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(0.8f),
                                offset = Offset(0f, 2f),
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }
            else if (!status.alarmEnabled && !status.streaming) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.VideocamOff, 
                        null, 
                        tint = Color.White.copy(0.8f), 
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Камера выключена", 
                        color = Color.White, 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.Bold,
                         style = LocalTextStyle.current.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(0.8f),
                                offset = Offset(0f, 2f),
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }
            else if (isLoadingPreview) {
                CircularProgressIndicator(color = Color.White)
            }
            else if (shouldShowPreview) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(0.4f), CircleShape)
                        .border(1.dp, Color.White.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }

        // КНОПКА ОБНОВЛЕНИЯ
        if (mqttConnected && status.alarmEnabled && !isOffline) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                IconButton(
                    onClick = { requestPreview() },
                    enabled = !isLoadingPreview,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(0.3f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        
        // НИЖНИЙ ТЕКСТ
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "КАМЕРА 01", 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Bold, 
                color = Color.White, 
                letterSpacing = 1.sp,
                style = LocalTextStyle.current.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(0.8f),
                        offset = Offset(0f, 1f),
                        blurRadius = 3f
                    )
                )
            )
            if (!isOffline && mqttConnected && (status.alarmEnabled || status.streaming)) {
                 Text(
                    if (isLoadingPreview) "Загрузка..." else "Нажмите для трансляции", 
                    fontSize = 12.sp, 
                    color = Color.White.copy(0.9f),
                    style = LocalTextStyle.current.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(0.8f),
                            offset = Offset(0f, 1f),
                            blurRadius = 3f
                        )
                    )
                )
            }
        }
    }
}



@Composable
fun SensorCard(title: String, value: String, icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PremiumCard)
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = PremiumTextSec,
                    lineHeight = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    color = PremiumText,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}


@Composable
fun SlideActionControl(
    isArmed: Boolean,
    isEnabled: Boolean = true,
    onSlide: (Boolean) -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val trackHeight = 64.dp
    val thumbSize = 56.dp
    val padding = 4.dp

    val trackColor = if (!isEnabled) PremiumDisabled.copy(0.2f) 
                     else if (isArmed) PremiumDanger.copy(0.25f) else PremiumSuccess.copy(0.25f)
    val thumbColor = if (!isEnabled) PremiumDisabled 
                     else if (isArmed) PremiumDanger else PremiumSuccess

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(100.dp))
            .background(PremiumCard)
            .border(1.dp, trackColor, RoundedCornerShape(100.dp))
            .alpha(if (isEnabled) 1f else 0.5f)
    ) {
        val density = LocalDensity.current
        val maxOffsetPx = remember(maxWidth) {
            with(density) {
                (maxWidth - thumbSize - padding * 2).toPx()
            }
        }

        val anim = remember { Animatable(0f) }
        var isProcessing by remember { mutableStateOf(false) }

        val progress = (anim.value / (if (maxOffsetPx > 0f) maxOffsetPx else 1f)).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(
                    Brush.horizontalGradient(
                        listOf(thumbColor.copy(0.16f), thumbColor.copy(0.34f))
                    )
                )
        )

        Text(
            text = if (!isEnabled) "СИСТЕМА НЕДОСТУПНА" 
                   else if (isArmed) "СНЯТЬ С ОХРАНЫ" else "ПОСТАВИТЬ НА ОХРАНУ",
            modifier = Modifier.align(Alignment.Center),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = PremiumTextSec.copy(alpha = 1f - progress * 0.6f)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(anim.value.roundToInt(), 0) }
                .padding(padding)
                .size(thumbSize)
                .shadow(8.dp, CircleShape)
                .background(thumbColor, CircleShape)
                .draggable(
                    enabled = isEnabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val new = (anim.value + delta).coerceIn(0f, maxOffsetPx)
                            anim.snapTo(new)
                        }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            if (isProcessing) return@launch
                            val velocityThreshold = 1500f
                            val shouldTrigger =
                                anim.value / (if (maxOffsetPx > 0f) maxOffsetPx else 1f) > 0.75f || velocity > velocityThreshold
                            if (shouldTrigger) {
                                isProcessing = true
                                anim.animateTo(
                                    targetValue = maxOffsetPx,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onSlide(!isArmed)
                                delay(260)
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = 320,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                isProcessing = false
                            } else {
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val rotation = (progress * 10f) - 5f
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                    }
            )
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PremiumBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                Icons.Rounded.Security,
                null,
                tint = PremiumAccent,
                modifier = Modifier.size(64.dp)
            )
            Text("Требуется доступ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
            ) {
                Text("Разрешить")
            }
        }
    }
}
