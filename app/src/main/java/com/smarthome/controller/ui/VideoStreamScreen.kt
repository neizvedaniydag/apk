package com.smarthome.controller.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarthome.controller.utils.MqttManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

// --- PREMIUM PALETTE ---
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumAccent = Color(0xFF3B82F6)
private val PremiumDanger = Color(0xFFEF4444)
private val PremiumSuccess = Color(0xFF10B981)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)

@Composable
fun VideoStreamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isStreaming by remember { mutableStateOf(true) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isLiveFrameReceived by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(0) }
    var connectionStatus by remember { mutableStateOf("Подключение...") }
    var showControls by remember { mutableStateOf(true) }
    var isFullScreenCrop by remember { mutableStateOf(false) }
    
    var lastFrameTime by remember { mutableStateOf(0L) }
    var frameCount by remember { mutableStateOf(0) }
    var fpsStartTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var droppedFrames by remember { mutableStateOf(0) }
    
    // 🔥 НОВОЕ: Переменная для отслеживания обработки кадра
    var isProcessingFrame by remember { mutableStateOf(false) }
    
    // Таймер зависания
    LaunchedEffect(isStreaming) {
        while(isStreaming) {
            kotlinx.coroutines.delay(5000) // Увеличено до 5 сек
            if (System.currentTimeMillis() - lastFrameTime > 5000 && isLiveFrameReceived) {
                isLiveFrameReceived = false
                connectionStatus = "Соединение потеряно"
                Log.w("VideoStream", "⚠️ Нет кадров 5 секунд")
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        MqttManager.sendCameraCommand(context, "STREAM_ON")
        Log.i("VideoStream", "📹 Отправлена команда STREAM_ON")
    }

    DisposableEffect(Unit) {
        // 🔥 ИСПРАВЛЕНО: Эффективное декодирование bitmap
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565 // Экономия 50% памяти
            inDither = false
            inScaled = false
            inPurgeable = true
            inInputShareable = true
        }
        
        MqttManager.subscribeToVideoStream { frameData, fpsUpdate, status ->
            when {
                frameData != null -> {
                    // 🔥 КРИТИЧНО: Пропускаем кадр если предыдущий еще обрабатывается
                    if (isProcessingFrame) {
                        droppedFrames++
                        Log.d("VideoStream", "⏭️ Кадр пропущен (занят обработкой)")
                        return@subscribeToVideoStream
                    }
                    
                    isProcessingFrame = true
                    
                    scope.launch(Dispatchers.Default) { // Default вместо IO для CPU-bound задач
                        try {
                            // Проверка валидности JPEG
                            if (frameData.size < 4) {
                                Log.w("VideoStream", "⚠️ Слишком маленький кадр: ${frameData.size} байт")
                                isProcessingFrame = false
                                return@launch
                            }
                            
                            val hasStart = frameData[0] == 0xFF.toByte() && frameData[1] == 0xD8.toByte()
                            val hasEnd = frameData[frameData.size - 2] == 0xFF.toByte() && 
                                        frameData[frameData.size - 1] == 0xD9.toByte()
                            
                            if (!hasStart || !hasEnd) {
                                Log.w("VideoStream", "⚠️ Битый JPEG: start=$hasStart end=$hasEnd")
                                isProcessingFrame = false
                                return@launch
                            }
                            
                            // 🔥 ИСПРАВЛЕНО: Декодирование с оптимизациями
                            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
                            
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    // Освобождаем старый bitmap
                                    currentFrame?.recycle()
                                    
                                    currentFrame = bitmap
                                    isLiveFrameReceived = true
                                    connectionStatus = "В эфире"
                                    
                                    // Подсчет FPS
                                    frameCount++
                                    val currentTime = System.currentTimeMillis()
                                    lastFrameTime = currentTime
                                    
                                    if (currentTime - fpsStartTime >= 1000) {
                                        fps = frameCount
                                        if (droppedFrames > 0) {
                                            Log.d("VideoStream", "📊 FPS: $fps (пропущено: $droppedFrames)")
                                        }
                                        frameCount = 0
                                        droppedFrames = 0
                                        fpsStartTime = currentTime
                                    }
                                    
                                    Log.d("VideoStream", "✅ Кадр отображен: ${bitmap.width}x${bitmap.height}, ${frameData.size} байт")
                                }
                            } else {
                                Log.e("VideoStream", "❌ Декодирование вернуло null")
                            }
                        } catch (e: Exception) {
                            Log.e("VideoStream", "❌ Ошибка декодирования", e)
                            connectionStatus = "Ошибка: ${e.message}"
                        } finally {
                            isProcessingFrame = false
                        }
                    }
                }
                
                fpsUpdate != null -> {
                    fps = fpsUpdate
                    Log.d("VideoStream", "📊 FPS обновлен: $fpsUpdate")
                }
                
                status != null -> {
                    connectionStatus = status
                    Log.d("VideoStream", "ℹ️ Статус: $status")
                }
            }
        }
        
        onDispose { 
            Log.i("VideoStream", "🛑 Отключение стрима")
            MqttManager.sendCameraCommand(context, "STREAM_OFF")
            MqttManager.unsubscribeFromVideoStream()
            // Освобождаем bitmap
            currentFrame?.recycle()
            currentFrame = null
        }
    }
    
    // --- UI ОТОБРАЖЕНИЕ ---
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showControls = !showControls },
            contentAlignment = Alignment.Center
        ) {
            if (currentFrame != null) {
                Image(
                    bitmap = currentFrame!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isFullScreenCrop) ContentScale.Crop else ContentScale.Fit
                )
                
                // Затемнение если нет свежих кадров
                if (!isLiveFrameReceived && isStreaming) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)))
                }
            }
            
            // Индикатор загрузки
            if (isStreaming && !isLiveFrameReceived) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = PremiumAccent, strokeWidth = 3.dp)
                    Text(
                        if (currentFrame == null) "ПОДКЛЮЧЕНИЕ..." else "БУФЕРИЗАЦИЯ...", 
                        fontSize = 12.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = PremiumText
                    )
                    Text(
                        connectionStatus,
                        fontSize = 10.sp,
                        color = PremiumTextSec
                    )
                }
            } else if (!isStreaming && currentFrame == null) {
                Text("ТРАНСЛЯЦИЯ ОСТАНОВЛЕНА", fontSize = 12.sp, color = PremiumTextSec)
            }
        }
        
        // ВЕРХНЯЯ ПАНЕЛЬ
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.8f), Color.Transparent)))
                    .padding(20.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack, 
                    modifier = Modifier.background(Color.White.copy(0.1f), CircleShape).size(42.dp)
                ) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).background(
                            if (isStreaming && isLiveFrameReceived) PremiumDanger else Color.Gray, 
                            CircleShape
                        )
                    )
                    Text("ЭФИР", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Box(
                    Modifier
                        .background(Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$fps FPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PremiumAccent)
                }
            }
        }
        
        // НИЖНЯЯ ПАНЕЛЬ
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f))))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween, 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StreamInfoItem(Icons.Rounded.Hd, "QVGA")
                        StreamInfoItem(
                            Icons.Rounded.Wifi, 
                            if (isLiveFrameReceived) "Отлично" else "Буфер"
                        )
                    }
                    IconButton(
                        onClick = { isFullScreenCrop = !isFullScreenCrop }, 
                        modifier = Modifier.background(Color.White.copy(0.1f), CircleShape).size(40.dp)
                    ) {
                        Icon(
                            if (isFullScreenCrop) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, 
                            null, 
                            tint = Color.White
                        )
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (isStreaming) {
                                    MqttManager.sendCameraCommand(context, "STREAM_OFF")
                                    isStreaming = false
                                    isLiveFrameReceived = false 
                                    connectionStatus = "Пауза"
                                    Log.i("VideoStream", "⏸️ Стрим поставлен на паузу")
                                } else {
                                    MqttManager.sendCameraCommand(context, "STREAM_ON")
                                    isStreaming = true
                                    connectionStatus = "Подключение..."
                                    Log.i("VideoStream", "▶️ Стрим возобновлен")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isStreaming) PremiumCard else PremiumSuccess
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, 
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(if (isStreaming) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                            Text(
                                if (isStreaming) "ПАУЗА" else "ПРОДОЛЖИТЬ", 
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Button(
                        onClick = { 
                            scope.launch(Dispatchers.IO) { 
                                MqttManager.sendCameraCommand(context, "STATUS")
                                Log.i("VideoStream", "🔄 Запрос статуса")
                            } 
                        },
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumCard),
                        contentPadding = PaddingValues(0.dp)
                    ) { 
                        Icon(Icons.Rounded.Refresh, null, tint = Color.White) 
                    }
                }
            }
        }
    }
}

@Composable
fun StreamInfoItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(Color.White.copy(0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, null, tint = PremiumTextSec, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 11.sp, color = PremiumTextSec, fontWeight = FontWeight.Medium)
    }
}
