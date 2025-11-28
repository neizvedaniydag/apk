package com.smarthome.controller.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarthome.controller.data.ConnectionMode
import com.smarthome.controller.data.MqttSettings
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.utils.CrashHandler

private val ModernBlue = Color(0xFF5B8DEF)
private val ModernPurple = Color(0xFF8B5CF6)
private val ModernGreen = Color(0xFF10B981)
private val ModernOrange = Color(0xFFF59E0B)
private val ModernRed = Color(0xFFEF4444)
private val DarkOverlay = Color(0xFF1E293B)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    
    var phoneNumber by remember { mutableStateOf(SystemState.phoneNumber) }
    var autoUpdate by remember { mutableStateOf(SystemState.autoUpdateEnabled) }
    var connectionMode by remember { mutableStateOf(SystemState.connectionMode) }
    var showSavedMessage by remember { mutableStateOf(false) }
    
    // MQTT настройки
    var mqttServer by remember { mutableStateOf(SystemState.mqttSettings.server) }
    var mqttPort by remember { mutableStateOf(SystemState.mqttSettings.port.toString()) }
    var mqttUser by remember { mutableStateOf(SystemState.mqttSettings.username) }
    var mqttPass by remember { mutableStateOf(SystemState.mqttSettings.password) }
    var showMqttSettings by remember { mutableStateOf(connectionMode != ConnectionMode.SMS_ONLY) }
    
    // Проверка краш лога
    var crashLog by remember { mutableStateOf(CrashHandler.getCrashLog(context)) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF0F4FF), Color(0xFFE8F0FE))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 32.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, 
                    modifier = Modifier.size(20.dp), tint = ModernBlue)
            }
            Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
        }
        
        Spacer(Modifier.height(16.dp))
        
        // ========== КНОПКА КРАШ ЛОГА - НОВОЕ ==========
        if (crashLog != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ModernRed.copy(0.1f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp)
                                .background(ModernRed.copy(0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, 
                                modifier = Modifier.size(20.dp), tint = ModernRed)
                        }
                        Column {
                            Text("Обнаружен краш", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ModernRed)
                            Text("Приложение завершилось с ошибкой", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("crash_log", crashLog)
                            clipboard.setPrimaryClip(clip)
                            
                            android.widget.Toast.makeText(context, "✅ Лог скопирован! Отправьте разработчику", android.widget.Toast.LENGTH_LONG).show()
                            
                            CrashHandler.clearCrashLog(context)
                            crashLog = null
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ModernRed)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), 
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Скопировать лог ошибки", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
        }
        
        // ========== РЕЖИМ ПОДКЛЮЧЕНИЯ ==========
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .background(Brush.radialGradient(listOf(ModernOrange, ModernPurple)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.SettingsInputAntenna, contentDescription = null, 
                            modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                    Column {
                        Text("Режим подключения", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
                        Text("Сохраняется автоматически", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectionModeItem(
                        title = "Гибридный (SMS + MQTT)",
                        subtitle = "Рекомендуемый режим",
                        selected = connectionMode == ConnectionMode.HYBRID,
                        onClick = { 
                            connectionMode = ConnectionMode.HYBRID
                            showMqttSettings = true
                            SystemState.connectionMode = ConnectionMode.HYBRID
                            showSavedMessage = true
                        }
                    )
                    ConnectionModeItem(
                        title = "Только MQTT",
                        subtitle = "Быстрее, требует WiFi",
                        selected = connectionMode == ConnectionMode.MQTT_ONLY,
                        onClick = { 
                            connectionMode = ConnectionMode.MQTT_ONLY
                            showMqttSettings = true
                            SystemState.connectionMode = ConnectionMode.MQTT_ONLY
                            showSavedMessage = true
                        }
                    )
                    ConnectionModeItem(
                        title = "Только SMS",
                        subtitle = "Работает везде",
                        selected = connectionMode == ConnectionMode.SMS_ONLY,
                        onClick = { 
                            connectionMode = ConnectionMode.SMS_ONLY
                            showMqttSettings = false
                            SystemState.connectionMode = ConnectionMode.SMS_ONLY
                            showSavedMessage = true
                        }
                    )
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        // ========== MQTT НАСТРОЙКИ ==========
        AnimatedVisibility(
            visible = showMqttSettings,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp)
                                .background(ModernBlue.copy(0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Cloud, contentDescription = null, 
                                modifier = Modifier.size(20.dp), tint = ModernBlue)
                        }
                        Column {
                            Text("MQTT Сервер", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
                            Text("ClusterFly.ru подключение", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    
                    OutlinedTextField(
                        value = mqttServer,
                        onValueChange = { mqttServer = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Сервер") },
                        placeholder = { Text("srv2.clusterfly.ru") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    OutlinedTextField(
                        value = mqttPort,
                        onValueChange = { mqttPort = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Порт") },
                        placeholder = { Text("9991") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    OutlinedTextField(
                        value = mqttUser,
                        onValueChange = { mqttUser = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пользователь") },
                        placeholder = { Text("user_XXXXX") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    OutlinedTextField(
                        value = mqttPass,
                        onValueChange = { mqttPass = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пароль") },
                        placeholder = { Text("***********") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Button(
                        onClick = {
                            SystemState.mqttSettings = MqttSettings(
                                server = mqttServer,
                                port = mqttPort.toIntOrNull() ?: 9991,
                                username = mqttUser,
                                password = mqttPass
                            )
                            showSavedMessage = true
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Brush.horizontalGradient(listOf(ModernBlue, ModernPurple)), 
                                    RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), 
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Сохранить MQTT", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        // Phone Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .background(Brush.radialGradient(listOf(ModernBlue, ModernPurple)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, 
                            modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                    Column {
                        Text("Номер Arduino", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkOverlay)
                        Text("Номер системы умного дома", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("+79964228371") },
                    leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = null, tint = ModernBlue) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ModernBlue,
                        unfocusedBorderColor = Color.Gray.copy(0.3f),
                        cursorColor = ModernBlue
                    )
                )
                
                Button(
                    onClick = {
                        SystemState.phoneNumber = phoneNumber
                        showSavedMessage = true
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Brush.horizontalGradient(listOf(ModernBlue, ModernPurple)), 
                                RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), 
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Сохранить номер", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                
                AnimatedVisibility(visible = showSavedMessage, 
                    enter = expandVertically() + fadeIn(), 
                    exit = shrinkVertically() + fadeOut()) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(ModernGreen.copy(0.1f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), 
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, 
                                tint = ModernGreen, modifier = Modifier.size(18.dp))
                            Text("Настройки сохранены", fontSize = 13.sp, 
                                fontWeight = FontWeight.Medium, color = ModernGreen)
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        // Auto Update
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(18.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), 
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .background(ModernBlue.copy(0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, 
                            tint = ModernBlue, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text("Автообновление", fontSize = 15.sp, 
                            fontWeight = FontWeight.SemiBold, color = DarkOverlay)
                        Text("Запрос статуса через 4 сек", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Switch(
                    checked = autoUpdate,
                    onCheckedChange = {
                        autoUpdate = it
                        SystemState.autoUpdateEnabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ModernGreen,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.Gray.copy(0.3f)
                    )
                )
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        // Info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ModernBlue.copy(0.05f), RoundedCornerShape(18.dp))
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = ModernBlue, 
                    modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Подсказка", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, 
                        color = DarkOverlay)
                    Text(
                        "Режим подключения сохраняется автоматически при выборе.\n" +
                        "MQTT по умолчанию настроен на ClusterFly.ru",
                        fontSize = 11.sp, color = Color.Gray, lineHeight = 16.sp
                    )
                }
            }
        }
    }
    
    LaunchedEffect(showSavedMessage) {
        if (showSavedMessage) {
            kotlinx.coroutines.delay(2000)
            showSavedMessage = false
        }
    }
}

@Composable
fun ConnectionModeItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) ModernBlue.copy(0.1f) else Color.Gray.copy(0.05f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, 
                    color = if (selected) ModernBlue else DarkOverlay)
                Text(subtitle, fontSize = 11.sp, color = Color.Gray)
            }
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = ModernBlue)
            )
        }
    }
}
