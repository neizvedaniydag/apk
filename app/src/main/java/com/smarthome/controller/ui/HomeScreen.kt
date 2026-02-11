package com.smarthome.controller.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.ui.graphics.graphicsLayer

// --- PREMIUM PALETTE ---
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumAccent = Color(0xFF3B82F6)
private val PremiumDanger = Color(0xFFEF4444)
private val PremiumSuccess = Color(0xFF10B981)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)

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
                onVideoClick = onNavigateToVideo,      // ✅ ИСПРАВЛЕНО!
                onHistoryClick = onNavigateToHistory,  // ✅ ИСПРАВЛЕНО!
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

    val sendCommand: (String) -> Unit = { command ->
        sendingCommand = command

        if (command == "DISARM") SystemState.clearAlarm()

        when (SystemState.connectionMode) {
            ConnectionMode.SMS_ONLY -> {
                SmsSender.sendCommand(context, command)
                scope.launch {
                    delay(1500)
                    sendingCommand = null
                }
            }
            ConnectionMode.MQTT_ONLY -> {
                scope.launch(Dispatchers.IO) {
                    MqttManager.sendCommand(context, command)
                    delay(1500)
                    sendingCommand = null
                }
            }
            ConnectionMode.HYBRID -> {
                SmsSender.sendCommand(context, command)
                scope.launch(Dispatchers.IO) {
                    MqttManager.sendCommand(context, command)
                    delay(1500)
                    sendingCommand = null
                }
            }
        }
    }

    // ✅ ПРАВИЛЬНО: Автообновление времени [web:471]
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    // Определяем, есть ли реальное подключение к системе (ESP, а не просто к серверу)
    val lastDataReceived by MqttManager.lastDataReceived.collectAsState()
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick = System.currentTimeMillis()
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
    val isSystemAlive = lastDataReceived > 0 &&
            (System.currentTimeMillis() - lastDataReceived) < 30_000

    val isOnline = when (SystemState.connectionMode) {
        ConnectionMode.SMS_ONLY -> true
        else -> mqttState.isConnected && isSystemAlive
    }

    // Трекер новых тревог для бейджа на кнопке истории
    var hasUnseenAlarm by remember { mutableStateOf(false) }
    LaunchedEffect(status.isAlarm) {
        if (status.isAlarm) {
            hasUnseenAlarm = true
        }
    }

    // Pull-to-refresh
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableStateOf(0f) }
    val pullThreshold = 200f
    val animatedOffset by animateFloatAsState(
        targetValue = pullOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pullOffset"
    )

    val pullToRefreshConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && pullOffset > 0) {
                    val consumed = available.y.coerceAtLeast(-pullOffset)
                    pullOffset += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && !isRefreshing) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceAtMost(pullThreshold * 1.5f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset >= pullThreshold && !isRefreshing) {
                    isRefreshing = true
                    pullOffset = 120f
                } else {
                    pullOffset = 0f
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            sendCommand("STATUS")
            delay(2000)
            isRefreshing = false
            pullOffset = 0f
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp)
                .nestedScroll(pullToRefreshConnection)
        ) {
            // Индикатор — в освободившемся пространстве сверху
            if (animatedOffset > 10f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { animatedOffset.toDp() }),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = PremiumAccent
                        )
                    } else {
                        val progress = (animatedOffset / pullThreshold).coerceIn(0f, 1f)
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = PremiumAccent.copy(alpha = progress)
                        )
                    }
                }
            }

            // Весь контент сдвигается вниз
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, animatedOffset.roundToInt()) }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // 1. HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Добро пожаловать", fontSize = 13.sp, color = PremiumTextSec)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Мой Дом", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        // Точка-индикатор подключения
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isOnline) PremiumSuccess else PremiumDanger,
                                    CircleShape
                                )
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Кнопка истории с бейджем тревоги
                    Box {
                        IconButton(
                            onClick = {
                                hasUnseenAlarm = false
                                onHistoryClick()
                            },
                            modifier = Modifier
                                .background(PremiumCard, CircleShape)
                                .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(Icons.Rounded.History, null, tint = PremiumAccent)
                        }
                        // Красный бейдж
                        if (hasUnseenAlarm) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(12.dp)
                                    .background(PremiumDanger, CircleShape)
                                    .border(1.5.dp, PremiumBg, CircleShape)
                            )
                        }
                    }
                    IconButton(
                        onClick = { sendCommand("STATUS") },
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = PremiumText)
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Rounded.Settings, null, tint = PremiumTextSec)
                    }
                }
            }

            // 2. STATUS INDICATOR (отправка команды)
            AnimatedVisibility(visible = sendingCommand != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PremiumAccent.copy(0.2f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = PremiumAccent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Отправка $sendingCommand...", fontSize = 13.sp, color = PremiumAccent)
                }
            }

            // 3. HERO CARD — только при охране или тревоге
            AnimatedVisibility(
                visible = status.alarmEnabled || status.isAlarm,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                StatusHeroCard(status)
            }

            // 4. OFFLINE BANNER — когда нет подключения
            AnimatedVisibility(
                visible = !isOnline,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(PremiumDanger.copy(0.12f))
                        .border(1.dp, PremiumDanger.copy(0.3f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.WifiOff, null, tint = PremiumDanger, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Нет связи с системой", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PremiumDanger)
                            Text("Проверьте подключение в настройках", fontSize = 12.sp, color = PremiumTextSec)
                        }
                    }
                }
            }

            // 5. VIDEO PREVIEW (только при интернет-режимах)
            if (SystemState.connectionMode != ConnectionMode.SMS_ONLY) {
                Box(
                    modifier = if (!isOnline) Modifier
                        .alpha(0.4f)
                        .blur(4.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    else Modifier
                ) {
                    VideoPreviewCard(
                        onClick = onVideoClick,
                        mqttConnected = mqttState.isConnected
                    )
                }
            }

            // 6. SENSORS GRID
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "ДАТЧИКИ И СОСТОЯНИЕ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PremiumTextSec,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Box(
                    modifier = if (!isOnline) Modifier
                        .alpha(0.4f)
                        .blur(4.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    else Modifier
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SensorCard(
                                title = "Движение",
                                value = if (status.pirStatus == "MOTION") "Обнаружено" else "Отсутствует",
                                icon = Icons.Rounded.DirectionsWalk,
                                color = if (status.pirStatus == "MOTION") PremiumDanger else PremiumSuccess
                            )
                            SensorCard(
                                title = "Уровень звука",
                                value = "${status.soundLevel} dB",
                                icon = Icons.Rounded.GraphicEq,
                                color = PremiumAccent
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SensorCard(
                                title = "Система",
                                value = if (isOnline) "Подключена" else "Не в сети",
                                icon = if (isOnline) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                                color = if (isOnline) PremiumSuccess else PremiumDanger
                            )
                            SensorCard(
                                title = "Подключение",
                                value = when (SystemState.connectionMode) {
                                    ConnectionMode.MQTT_ONLY -> if (mqttState.isConnected) "Онлайн" else "Оффлайн"
                                    ConnectionMode.SMS_ONLY -> "SMS"
                                    ConnectionMode.HYBRID -> if (mqttState.isConnected) "Онлайн" else "Только SMS"
                                },
                                icon = when (SystemState.connectionMode) {
                                    ConnectionMode.SMS_ONLY -> Icons.Rounded.Sms
                                    ConnectionMode.HYBRID -> Icons.Rounded.SyncAlt
                                    else -> Icons.Rounded.Cloud
                                },
                                color = if (isOnline) PremiumAccent else PremiumTextSec
                            )
                        }
                    }
                }
            }

            // Время обновления
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
                color = PremiumTextSec.copy(0.4f)
            )

            Spacer(Modifier.height(8.dp))
        }
        } // nestedScroll Box

        // СЛАЙДЕР — прибит к низу экрана
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, PremiumBg.copy(0.95f), PremiumBg)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SlideActionControl(
                isArmed = status.alarmEnabled,
                enabled = isOnline,
                onSlide = { shouldArm ->
                    sendCommand(if (shouldArm) "ARM" else "DISARM")
                }
            )
        }
    }
}

@Composable
fun StatusHeroCard(status: SystemStatus) {
    val gradient = if (status.isAlarm) {
        Brush.linearGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
    } else if (status.alarmEnabled) {
        Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF047857)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF4B5563), Color(0xFF374151)))
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
            Column {
                Text(
                    text = if (status.isAlarm) "ТРЕВОГА!"
                    else if (status.alarmEnabled) "ОПОВЕЩЕНИЕ ВКЛЮЧЕНО"
                    else "ОПОВЕЩЕНИЕ ОТКЛЮЧЕНО",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = if (status.isAlarm) "Обнаружено вторжение"
                    else "Все системы в норме",
                    fontSize = 13.sp,
                    color = Color.White.copy(0.8f)
                )
            }
            Icon(
                imageVector = if (status.isAlarm) Icons.Rounded.Warning else Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier.size(40.dp).alpha(0.8f),
                tint = Color.White
            )
        }
    }
}

@Composable
fun VideoPreviewCard(onClick: () -> Unit, mqttConnected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val previewBitmap by MqttManager.latestPreview.collectAsState()
    val lastDataReceived by MqttManager.lastDataReceived.collectAsState()

    var lastPreviewRequest by remember { mutableStateOf(0L) }
    var isLoadingPreview by remember { mutableStateOf(false) }

    // Тикер для пересчёта isSystemOnline каждые 5 секунд
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick = System.currentTimeMillis()
        }
    }

    // Система считается реально онлайн, если данные приходили в последние 30 секунд
    @Suppress("UNUSED_EXPRESSION")
    tick // используем tick чтобы Compose пересчитывал
    val isSystemOnline = lastDataReceived > 0 &&
            (System.currentTimeMillis() - lastDataReceived) < 30_000

    fun requestPreview() {
        if (!mqttConnected) return

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

    LaunchedEffect(mqttConnected) {
        if (mqttConnected) {
            delay(2000)
            requestPreview()

            while (mqttConnected) {
                delay(15000)
                if (mqttConnected) {
                    requestPreview()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.77f)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = mqttConnected) { onClick() }
    ) {
        // === BACKGROUND IMAGE ===
        if (previewBitmap != null) {
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

        // 🔥 МИНИМАЛЬНЫЙ Vignette
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(0.2f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(0.4f)
                        )
                    )
                )
        )

        // 🔥 ПРИОРИТЕТ 1: OFFLINE - показываем ТОЛЬКО когда нет связи
        if (!mqttConnected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.WifiOff,
                        null,
                        tint = Color.White.copy(0.6f),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        "Нет подключения",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } 
        // 🔥 ПРИОРИТЕТ 2: LOADING - показываем только если есть связь И идёт загрузка
        else if (isLoadingPreview) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        "Загрузка",
                        color = Color.White.copy(0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        // 🔥 ПРИОРИТЕТ 3: PLAY BUTTON - показываем только если всё ок
        else {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(68.dp)
                    .background(
                        Color.Black.copy(0.4f),
                        CircleShape
                    )
                    .border(
                        1.5.dp,
                        Color.White.copy(0.25f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 🔥 ВЕРХНЯЯ ПАНЕЛЬ - показываем всегда
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(0.3f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ЭФИР - только когда система реально онлайн (данные приходят)
                if (isSystemOnline && !isLoadingPreview) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color(0xFFEF4444).copy(0.95f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "ЭФИР",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                
                // Кнопка обновления - только когда подключено
                if (mqttConnected) {
                    IconButton(
                        onClick = { requestPreview() },
                        enabled = !isLoadingPreview,
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                Color.Black.copy(0.25f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Обновить",
                            tint = Color.White.copy(if (isLoadingPreview) 0.4f else 0.9f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        
        // 🔥 НИЖНЯЯ ПАНЕЛЬ - показываем всегда
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(0.5f)
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "КАМЕРА 01",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                letterSpacing = 0.8.sp
            )
            Text(
                when {
                    !mqttConnected -> "Нет связи"
                    isLoadingPreview -> "Загрузка"
                    previewBitmap != null -> "Нажмите для трансляции"
                    else -> "Ожидание данных"
                }, 
                fontSize = 12.sp, 
                color = Color.White.copy(0.7f)
            )
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
                    lineHeight = 13.sp,  // 🔥 Минимальная высота для букв
                    maxLines = 1
                )
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,  // 🔥 Минимальная высота для букв с хвостиками
                    maxLines = 1,
                    color = PremiumText,
                    modifier = Modifier.padding(top = 2.dp)  // 🔥 Микрозазор
                )
            }
        }
    }
}


@Composable
fun SlideActionControl(
    isArmed: Boolean,
    enabled: Boolean = true,
    onSlide: (Boolean) -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val trackHeight = 64.dp
    val thumbSize = 56.dp
    val padding = 4.dp

    val disabledColor = PremiumTextSec.copy(0.3f)
    val trackColor = if (!enabled) disabledColor
        else if (isArmed) PremiumDanger.copy(0.25f) else PremiumSuccess.copy(0.25f)
    val thumbColor = if (!enabled) PremiumTextSec
        else if (isArmed) PremiumDanger else PremiumSuccess

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(100.dp))
            .background(PremiumCard)
            .border(1.dp, trackColor, RoundedCornerShape(100.dp))
            .alpha(if (enabled) 1f else 0.5f)
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
            text = if (!enabled) "НЕТ СВЯЗИ С СИСТЕМОЙ"
                else if (isArmed) "СЛАЙД ДЛЯ ОТКЛЮЧЕНИЯ" else "СЛАЙД ДЛЯ ОХРАНЫ",
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
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
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
                imageVector = Icons.Rounded.ArrowForward,
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
