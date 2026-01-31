package com.smarthome.controller.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarthome.controller.data.ConnectionMode
import com.smarthome.controller.data.MqttSettings
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.utils.CrashHandler
import kotlinx.coroutines.delay

// --- ЦВЕТОВАЯ ПАЛИТРА (Идентична HomeScreen) ---
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumAccent = Color(0xFF3B82F6)
private val PremiumDanger = Color(0xFFEF4444)
private val PremiumSuccess = Color(0xFF10B981)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    
    // --- ДАННЫЕ ---
    var phoneNumber by remember { mutableStateOf(SystemState.phoneNumber.ifBlank { "+79964228371" }) }
    var autoUpdate by remember { mutableStateOf(SystemState.autoUpdateEnabled) }
    var connectionMode by remember { mutableStateOf(SystemState.connectionMode) }
    
    // MQTT Данные
    var mqttServer by remember { mutableStateOf(SystemState.mqttSettings.server.ifBlank { "srv2.clusterfly.ru" }) }
    var mqttPort by remember { mutableStateOf(if (SystemState.mqttSettings.port == 0) "9991" else SystemState.mqttSettings.port.toString()) }
    var mqttUser by remember { mutableStateOf(SystemState.mqttSettings.username.ifBlank { "user_4bd2b1f5" }) }
    var mqttPass by remember { mutableStateOf(SystemState.mqttSettings.password.ifBlank { "FnoQuMvkcV1ej" }) }
    
    // Логика отображения
    var showMqttSettings by remember { mutableStateOf(connectionMode != ConnectionMode.SMS_ONLY) }
    var showPhoneSettings by remember(connectionMode) { mutableStateOf(connectionMode != ConnectionMode.MQTT_ONLY) }
    var showSavedMessage by remember { mutableStateOf(false) }
    var crashLog by remember { mutableStateOf(CrashHandler.getCrashLog(context)) }
    
    // Инициализация дефолтных значений
    LaunchedEffect(Unit) {
        // 🔥 ИСПРАВЛЕНО: Используем функцию вместо прямого присваивания
        if (SystemState.phoneNumber.isBlank()) SystemState.savePhoneNumber(phoneNumber)
        if (SystemState.mqttSettings.server.isBlank()) {
            val def = MqttSettings(mqttServer, mqttPort.toInt(), mqttUser, mqttPass)
            // 🔥 ИСПРАВЛЕНО: Убран лишний аргумент, используем функцию
            SystemState.saveMqttSettings(def)
        }
    }
    
    // --- ГЛАВНЫЙ КОНТЕЙНЕР ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PremiumBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // --- 1. ШАПКА (Стиль как на Главном экране) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(PremiumCard, CircleShape)
                    .border(1.dp, Color.White.copy(0.1f), CircleShape)
                    .size(42.dp)
            ) {
                Icon(Icons.Rounded.ArrowBack, null, tint = PremiumText)
            }
            
            Column {
                Text("Конфигурация", fontSize = 14.sp, color = PremiumTextSec)
                Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PremiumText)
            }
        }

        // --- CRASH LOG (Появляется только при ошибке) ---
        if (crashLog != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(PremiumDanger.copy(0.1f))
                    .border(1.dp, PremiumDanger.copy(0.3f), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.BugReport, null, tint = PremiumDanger)
                        Text("ОБНАРУЖЕН СБОЙ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PremiumDanger)
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("crash_log", crashLog)
                            clipboard.setPrimaryClip(clip)
                            CrashHandler.clearCrashLog(context)
                            crashLog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumDanger),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Скопировать лог", fontSize = 14.sp)
                    }
                }
            }
        }

        // --- 2. РЕЖИМ ПОДКЛЮЧЕНИЯ ---
        SettingsGroup(title = "РЕЖИМ РАБОТЫ") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    title = "Гибридный (Рекомендуем)",
                    desc = "Интернет + SMS резерв. Максимальная надежность.",
                    icon = Icons.Rounded.Security,
                    selected = connectionMode == ConnectionMode.HYBRID,
                    onClick = { 
                        connectionMode = ConnectionMode.HYBRID
                        showMqttSettings = true; showPhoneSettings = true
                        // 🔥 ИСПРАВЛЕНО: Сначала сохраняем MQTT, потом режим
                        SystemState.saveMqttSettings(MqttSettings(mqttServer, mqttPort.toIntOrNull() ?: 9991, mqttUser, mqttPass))
                        SystemState.saveConnectionMode(ConnectionMode.HYBRID)
                    }
                )
                ModeCard(
                    title = "Только Интернет (MQTT)",
                    desc = "Быстрое управление. Экономит деньги на SMS.",
                    icon = Icons.Rounded.Cloud,
                    selected = connectionMode == ConnectionMode.MQTT_ONLY,
                    onClick = { 
                        connectionMode = ConnectionMode.MQTT_ONLY
                        showMqttSettings = true; showPhoneSettings = false
                        // 🔥 ИСПРАВЛЕНО
                        SystemState.saveMqttSettings(MqttSettings(mqttServer, mqttPort.toIntOrNull() ?: 9991, mqttUser, mqttPass))
                        SystemState.saveConnectionMode(ConnectionMode.MQTT_ONLY)
                    }
                )
                ModeCard(
                    title = "Только SMS (Оффлайн)",
                    desc = "Для мест с плохим интернетом. Полная автономность.",
                    icon = Icons.Rounded.Sms,
                    selected = connectionMode == ConnectionMode.SMS_ONLY,
                    onClick = { 
                        connectionMode = ConnectionMode.SMS_ONLY
                        showMqttSettings = false; showPhoneSettings = true
                        // 🔥 ИСПРАВЛЕНО
                        SystemState.saveConnectionMode(ConnectionMode.SMS_ONLY)
                    }
                )
            }
        }

        // --- 3. MQTT НАСТРОЙКИ ---
        AnimatedVisibility(visible = showMqttSettings) {
            SettingsGroup(title = "ПАРАМЕТРЫ СЕРВЕРА") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PremiumInput(value = mqttServer, onValueChange = { mqttServer = it }, label = "Адрес сервера", icon = Icons.Rounded.Dns)
                    PremiumInput(value = mqttPort, onValueChange = { mqttPort = it }, label = "Порт", icon = Icons.Rounded.Numbers)
                    PremiumInput(value = mqttUser, onValueChange = { mqttUser = it }, label = "Пользователь", icon = Icons.Rounded.Person)
                    PremiumInput(value = mqttPass, onValueChange = { mqttPass = it }, label = "Пароль", isPassword = true, icon = Icons.Rounded.Key)
                    
                    SaveButton(text = "Сохранить сервер") {
                        val newSettings = MqttSettings(mqttServer, mqttPort.toIntOrNull() ?: 9991, mqttUser, mqttPass)
                        // 🔥 ИСПРАВЛЕНО: Только функция, без прямого присваивания
                        SystemState.saveMqttSettings(newSettings)
                        showSavedMessage = true
                    }
                }
            }
        }

        // --- 4. НАСТРОЙКИ ТЕЛЕФОНА ---
        AnimatedVisibility(visible = showPhoneSettings) {
            SettingsGroup(title = "SIM-КАРТА УСТРОЙСТВА") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Укажите номер SIM-карты, которая стоит в охранной системе.",
                        fontSize = 13.sp, color = PremiumTextSec, lineHeight = 18.sp
                    )
                    PremiumInput(
                        value = phoneNumber, 
                        onValueChange = { phoneNumber = it }, 
                        label = "Номер телефона (+7...)", 
                        icon = Icons.Rounded.SimCard
                    )
                    
                    SaveButton(text = "Сохранить номер") {
                        // 🔥 ИСПРАВЛЕНО: Используем функцию
                        SystemState.saveGeneralSettings(phoneNumber, autoUpdate)
                        showSavedMessage = true
                    }
                }
            }
        }

        // --- 5. ЖИВОЕ ОБНОВЛЕНИЕ ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(PremiumCard)
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(PremiumAccent.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Sync, null, tint = PremiumAccent)
                    }
                    Column {
                        Text("Автообновление", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PremiumText)
                        Text("Опрос каждые 4 сек", fontSize = 12.sp, color = PremiumTextSec)
                    }
                }
                Switch(
                    checked = autoUpdate,
                    onCheckedChange = {
                        autoUpdate = it
                        // 🔥 ИСПРАВЛЕНО: Используем функцию вместо прямого присваивания
                        SystemState.saveGeneralSettings(phoneNumber, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PremiumSuccess,
                        uncheckedThumbColor = PremiumTextSec,
                        uncheckedTrackColor = PremiumBg
                    )
                )
            }
        }
        
        Spacer(Modifier.height(40.dp))
    }

    // --- ВСПЛЫВАШКА СОХРАНЕНИЯ ---
    if (showSavedMessage) {
        LaunchedEffect(Unit) {
            delay(2000)
            showSavedMessage = false
        }
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 50.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PremiumSuccess)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Настройки сохранены", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- КОМПОНЕНТЫ (БЕЗ ИЗМЕНЕНИЙ) ---

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PremiumTextSec, modifier = Modifier.padding(start = 8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(PremiumCard)
                .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            content()
        }
    }
}

@Composable
fun ModeCard(title: String, desc: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) PremiumAccent else Color.White.copy(0.05f)
    val bgColor = if (selected) PremiumAccent.copy(0.1f) else Color.Transparent
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(if (selected) PremiumAccent else PremiumBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (selected) Color.White else PremiumTextSec)
        }
        
        Spacer(Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PremiumText)
            Text(desc, fontSize = 12.sp, color = PremiumTextSec, lineHeight = 16.sp)
        }
        
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.CheckCircle, null, tint = PremiumAccent)
        }
    }
}

@Composable
fun PremiumInput(value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector, isPassword: Boolean = false) {
    val visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = PremiumTextSec)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(icon, null, tint = PremiumTextSec) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PremiumBg,
                unfocusedContainerColor = PremiumBg,
                focusedBorderColor = PremiumAccent,
                unfocusedBorderColor = Color.White.copy(0.1f),
                focusedTextColor = PremiumText,
                unfocusedTextColor = PremiumText,
                cursorColor = PremiumAccent
            ),
            visualTransformation = visualTransformation
        )
    }
}

@Composable
fun SaveButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
