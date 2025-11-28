package com.smarthome.controller.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val ModernBlue = Color(0xFF5B8DEF)
private val ModernPurple = Color(0xFF8B5CF6)
private val ModernGreen = Color(0xFF10B981)
private val ModernRed = Color(0xFFEF4444)
private val ModernYellow = Color(0xFFF59E0B)
private val DarkOverlay = Color(0xFF1E293B)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var systemStatus by remember { mutableStateOf(SystemState.currentStatus) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        SystemState.init(context)
        SystemState.onStatusUpdate = { newStatus ->
            systemStatus = newStatus
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

    AnimatedContent(
        targetState = when {
            !permissionsState.allPermissionsGranted -> 0
            showSettings -> 1
            else -> 2
        },
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "screen"
    ) { screen ->
        when (screen) {
            0 -> PermissionsScreen(onRequestPermissions = { permissionsState.launchMultiplePermissionRequest() })
            1 -> SettingsScreen(onBack = { showSettings = false })
            else -> MainContent(
                modifier = Modifier,
                status = systemStatus,
                onRefresh = {
                    scope.launch(Dispatchers.IO) {
                        when (SystemState.connectionMode) {
                            ConnectionMode.SMS_ONLY -> SmsSender.sendCommand(context, "STATUS")
                            ConnectionMode.MQTT_ONLY -> MqttManager.sendCommand(context, "STATUS")
                            ConnectionMode.HYBRID -> {
                                SmsSender.sendCommand(context, "STATUS")
                                MqttManager.sendCommand(context, "STATUS")
                            }
                        }
                    }
                },
                onSettingsClick = { showSettings = true }
            )
        }
    }
}

@Composable
fun PermissionsScreen(onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ModernPurple.copy(0.1f), ModernBlue.copy(0.1f)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Brush.radialGradient(listOf(ModernPurple, ModernBlue)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(50.dp), tint = Color.White)
            }

            Text("Необходимые разрешения", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Для работы приложения нужен доступ к SMS и уведомлениям", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 24.sp)

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ModernBlue)
            ) {
                Text("Предоставить доступ", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    status: SystemStatus,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var sendingStatus by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    DisposableEffect(Unit) {
        MqttManager.onConnectionStatusChange = { connected, message ->
            isSending = !connected || message.contains("Отправка") || message.contains("Подключение")
            sendingStatus = message
            
            if (message.contains("Статус обновлён") || message.contains("Подключено")) {
                isLoading = false
            }
        }
        onDispose {
            MqttManager.onConnectionStatusChange = null
        }
    }
    
    val currentMode by remember { derivedStateOf { SystemState.connectionMode } }
    
val sendCommand: (String) -> Unit = { command ->
    // При снятии с охраны - сбрасываем тревогу СРАЗУ!
    if (command == "DISARM") {
        SystemState.clearAlarm()  // ← УЖЕ ЕСТЬ!
    }
    
    when (currentMode) {
        ConnectionMode.SMS_ONLY -> {
            SmsSender.sendCommand(context, command)
        }
        ConnectionMode.MQTT_ONLY -> {
            isSending = true
            sendingStatus = "Отправка..."
            scope.launch(Dispatchers.IO) {
                MqttManager.sendCommand(context, command)
            }
        }
        ConnectionMode.HYBRID -> {
            SmsSender.sendCommand(context, command)
            isSending = true
            sendingStatus = "Отправка..."
            scope.launch(Dispatchers.IO) {
                MqttManager.sendCommand(context, command)
            }
        }
    }
}

    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF0F4FF), Color(0xFFE8F0FE))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 32.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Умный дом", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
                Text("Система безопасности", fontSize = 12.sp, color = Color.Gray)
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSettingsClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(20.dp), tint = ModernBlue)
            }
        }

        Spacer(Modifier.height(14.dp))

        // Alarm Alert - БЛОК ТРЕВОГИ!
        AnimatedVisibility(
            visible = status.isAlarm,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                CleanCard(modifier = Modifier.fillMaxWidth(), backgroundColor = ModernRed.copy(0.15f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).background(ModernRed, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Тревога!", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ModernRed)
                            Text("Сработала сигнализация", fontSize = 12.sp, color = Color.Gray)
                        }
                        IconButton(onClick = { SystemState.clearAlarm() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = ModernRed)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // Индикатор загрузки
        if (isLoading && currentMode != ConnectionMode.SMS_ONLY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Загрузка статуса от ESP32...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        
        // Индикатор отправки
        if (isSending) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = sendingStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Status Card
        StatusCard(status = status, onRefresh = onRefresh, enabled = !isSending)

        Spacer(Modifier.height(10.dp))

        // Buttons
        ControlButtonsSection(
            status = status,
            onCommand = sendCommand,
            enabled = !isSending
        )

        Spacer(Modifier.weight(1f))

        // Connection
        ConnectionInfo(
            mode = currentMode,
            lastUpdate = status.lastUpdate
        )
    }
}

@Composable
fun StatusCard(status: SystemStatus, onRefresh: () -> Unit, enabled: Boolean = true) {
    CleanCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Brush.radialGradient(listOf(ModernBlue, ModernPurple)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                    Text("Статус системы", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
                }
                StatusBadge(if (status.alarmEnabled) "Включено" else "Выключено", status.alarmEnabled)
            }

            HorizontalDivider(color = Color.Gray.copy(0.2f))

            ModernMetricCard(Icons.Rounded.Security, "Сигнализация", if (status.alarmEnabled) "Активна" else "Выкл", if (status.alarmEnabled) ModernGreen else Color.Gray)
            ModernMetricCard(Icons.Rounded.Block, "Блокировка", if (status.systemLocked) "Заблокировано" else "Разблокировано", if (status.systemLocked) ModernRed else Color.Gray)
            ModernMetricCard(Icons.Rounded.DirectionsWalk, "Датчик движения", if (status.pirStatus == "MOTION") "Обнаружено" else "Нет движения", if (status.pirStatus == "MOTION") ModernRed else Color.Gray)
            ModernMetricCard(Icons.Rounded.GraphicEq, "Уровень звука", "${status.soundLevel} дБ", ModernYellow)
            ModernMetricCard(Icons.Rounded.Schedule, "Обновлено", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.lastUpdate)), ModernBlue)
        }
    }
}

@Composable
fun ControlButtonsSection(status: SystemStatus, onCommand: (String) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModernActionButton(
            icon = if (status.alarmEnabled) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
            text = if (status.alarmEnabled) "Снять" else "Включить",
            gradient = if (status.alarmEnabled) listOf(ModernRed, ModernRed.copy(0.8f)) else listOf(ModernGreen, ModernGreen.copy(0.8f)),
            modifier = Modifier.weight(1f),
            enabled = enabled
        ) {
            onCommand(if (status.alarmEnabled) "DISARM" else "ARM")
        }
        ModernActionButton(
            icon = Icons.Rounded.Refresh,
            text = "Обновить",
            gradient = listOf(ModernBlue, ModernPurple),
            modifier = Modifier.weight(1f),
            enabled = enabled
        ) {
            onCommand("STATUS")
        }
    }
}

@Composable
fun ConnectionInfo(mode: ConnectionMode, lastUpdate: Long) {
    CleanCard(modifier = Modifier.fillMaxWidth(), backgroundColor = ModernBlue.copy(0.05f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(ModernBlue.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (mode) {
                        ConnectionMode.SMS_ONLY -> Icons.Rounded.Sms
                        ConnectionMode.MQTT_ONLY -> Icons.Rounded.Cloud
                        ConnectionMode.HYBRID -> Icons.Rounded.Sync
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = ModernBlue
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Режим подключения", fontSize = 11.sp, color = Color.Gray)
                Text(
                    when (mode) {
                        ConnectionMode.SMS_ONLY -> "Только SMS"
                        ConnectionMode.MQTT_ONLY -> "Только MQTT"
                        ConnectionMode.HYBRID -> "Гибридный режим"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkOverlay
                )
            }
        }
    }
}

@Composable
fun CleanCard(modifier: Modifier = Modifier, backgroundColor: Color = Color.White, content: @Composable () -> Unit) {
    Box(modifier = modifier.background(backgroundColor, RoundedCornerShape(18.dp))) {
        content()
    }
}

@Composable
fun StatusBadge(text: String, isActive: Boolean) {
    Box(
        modifier = Modifier
            .background(if (isActive) ModernGreen.copy(0.15f) else Color.Gray.copy(0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).background(if (isActive) ModernGreen else Color.Gray, CircleShape))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isActive) ModernGreen else Color.Gray)
        }
    }
}

@Composable
fun ModernMetricCard(icon: ImageVector, label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(color.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
            }
            Text(label, fontSize = 14.sp, color = DarkOverlay)
        }
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun ModernActionButton(
    icon: ImageVector,
    text: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(80.dp)
            .background(Brush.verticalGradient(if (enabled) gradient else listOf(Color.Gray, Color.Gray)), RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White.copy(if (enabled) 1f else 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(if (enabled) 1f else 0.5f), textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
