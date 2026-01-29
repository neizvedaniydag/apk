package com.smarthome.controller.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarthome.controller.utils.MqttManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val ModernBlue = Color(0xFF5B8DEF)
private val ModernPurple = Color(0xFF8B5CF6)
private val ModernGreen = Color(0xFF10B981)
private val ModernRed = Color(0xFFEF4444)
private val DarkOverlay = Color(0xFF1E293B)

@Composable
fun VideoStreamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isStreaming by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fps by remember { mutableStateOf(0) }
    var connectionStatus by remember { mutableStateOf("Отключено") }
    var streamQuality by remember { mutableStateOf("VGA") }
    var showControls by remember { mutableStateOf(true) }
    
    val frameBuffer = remember { ByteArrayOutputStream() }
    var lastFrameTime by remember { mutableStateOf(0L) }
    var frameCount by remember { mutableStateOf(0) }
    
    DisposableEffect(Unit) {
        MqttManager.subscribeToVideoStream { frameData, fpsUpdate, status ->
            scope.launch(Dispatchers.IO) {
                try {
                    when {
                        frameData != null -> {
                            frameBuffer.write(frameData)
                            
                            // Проверяем конец JPEG (FFD9)
                            val bytes = frameBuffer.toByteArray()
                            if (bytes.size >= 2 && 
                                bytes[bytes.size - 2] == 0xFF.toByte() && 
                                bytes[bytes.size - 1] == 0xD9.toByte()) {
                                
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                withContext(Dispatchers.Main) {
                                    currentFrame = bitmap
                                    frameCount++
                                    
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastFrameTime >= 1000) {
                                        fps = frameCount
                                        frameCount = 0
                                        lastFrameTime = currentTime
                                    }
                                }
                                frameBuffer.reset()
                            }
                        }
                        fpsUpdate != null -> {
                            fps = fpsUpdate
                        }
                        status != null -> {
                            connectionStatus = status
                        }
                    }
                } catch (e: Exception) {
                    connectionStatus = "Ошибка: ${e.message}"
                }
            }
        }
        
        onDispose {
            MqttManager.unsubscribeFromVideoStream()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(0.1f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onBack
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            "Видеонаблюдение",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isStreaming) ModernGreen else ModernRed,
                                        CircleShape
                                    )
                            )
                            Text(
                                connectionStatus,
                                fontSize = 12.sp,
                                color = Color.White.copy(0.7f)
                            )
                        }
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // FPS Badge
                    Box(
                        modifier = Modifier
                            .background(
                                ModernBlue.copy(0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$fps FPS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    
                    // Quality Badge
                    Box(
                        modifier = Modifier
                            .background(
                                ModernPurple.copy(0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            streamQuality,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
            
            // Video Display Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showControls = !showControls
                    },
                contentAlignment = Alignment.Center
            ) {
                if (currentFrame != null) {
                    Image(
                        bitmap = currentFrame!!.asImageBitmap(),
                        contentDescription = "Видео поток",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(0.3f)
                        )
                        Text(
                            if (isStreaming) "Загрузка видео..." else "Нет сигнала",
                            fontSize = 16.sp,
                            color = Color.White.copy(0.5f)
                        )
                    }
                }
                
                // Loading Indicator
                if (isStreaming && currentFrame == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = ModernBlue,
                        strokeWidth = 4.dp
                    )
                }
            }
            
            // Control Buttons
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(0.7f)
                                )
                            )
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StreamControlButton(
                            icon = if (isStreaming) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            text = if (isStreaming) "Остановить" else "Запустить",
                            gradient = if (isStreaming) 
                                listOf(ModernRed, ModernRed.copy(0.8f)) 
                            else 
                                listOf(ModernGreen, ModernGreen.copy(0.8f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            scope.launch(Dispatchers.IO) {
                                if (isStreaming) {
                                    MqttManager.sendCameraCommand(context, "STREAM_OFF")
                                    isStreaming = false
                                    currentFrame = null
                                    connectionStatus = "Отключено"
                                } else {
                                    MqttManager.sendCameraCommand(context, "STREAM_ON")
                                    isStreaming = true
                                    connectionStatus = "Подключение..."
                                }
                            }
                        }
                        
                        StreamControlButton(
                            icon = Icons.Rounded.Refresh,
                            text = "Обновить",
                            gradient = listOf(ModernBlue, ModernPurple),
                            modifier = Modifier.weight(1f)
                        ) {
                            scope.launch(Dispatchers.IO) {
                                MqttManager.sendCameraCommand(context, "STATUS")
                            }
                        }
                    }
                    
                    // Additional Info
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(0.1f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoChip(Icons.Rounded.SignalCellularAlt, "WiFi", "Хорошо")
                        InfoChip(Icons.Rounded.Memory, "Память", "72%")
                        InfoChip(Icons.Rounded.Schedule, "Время", "00:${String.format("%02d", frameCount)}")
                    }
                }
            }
        }
    }
}

@Composable
fun StreamControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(70.dp)
            .background(Brush.horizontalGradient(gradient), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.White.copy(0.7f)
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color.White.copy(0.5f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

