package com.smarthome.controller.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.smarthome.controller.data.HistoryEvent
import com.smarthome.controller.data.HistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// 🎨 ИСПОЛЬЗУЕМ ТУ ЖЕ ПАЛИТРУ
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumAccent = Color(0xFF3B82F6)
private val PremiumDanger = Color(0xFFEF4444)
private val PremiumSuccess = Color(0xFF10B981)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)
private val PremiumWarning = Color(0xFFF59E0B)

// ТИПЫ СОБЫТИЙ
enum class EventType(val icon: ImageVector, val color: Color, val label: String) {
    ALARM(Icons.Rounded.Warning, PremiumDanger, "Тревога"),
    PHOTO(Icons.Rounded.Image, PremiumAccent, "Фото"),
    MOTION(Icons.Rounded.DirectionsWalk, PremiumWarning, "Движение"),
    DOOR(Icons.Rounded.DoorFront, PremiumSuccess, "Дверь"),
    SYSTEM(Icons.Rounded.Settings, PremiumTextSec, "Система")
}

// ГРУППИРОВКА ПО ВРЕМЕНИ
sealed class TimeGroup(val title: String, val priority: Int) {
    object Today : TimeGroup("СЕГОДНЯ", 4)
    object Yesterday : TimeGroup("ВЧЕРА", 3)
    data class ThisWeek(val date: String) : TimeGroup(date.uppercase(), 2)
    data class Older(val date: String) : TimeGroup(date.uppercase(), 1)
}

// 🔥 ГРУППА СОБЫТИЙ (несколько событий близко по времени)
data class EventCluster(
    val events: List<HistoryEvent>,
    val timestamp: Long,
    val type: String
) {
    val mainEvent = events.first()
    val count = events.size
    val hasImages = events.any { it.imagePath != null }
    val images = events.mapNotNull { it.imagePath }
}

// 🔥 УМНАЯ ГРУППИРОВКА: объединяем события если они в пределах 2 минут и одного типа
private fun clusterEvents(events: List<HistoryEvent>): List<EventCluster> {
    if (events.isEmpty()) return emptyList()
    
    val clusters = mutableListOf<EventCluster>()
    var currentCluster = mutableListOf(events.first())
    
    for (i in 1 until events.size) {
        val prev = events[i - 1]
        val current = events[i]
        
        // Если события одного типа и в пределах 2 минут - объединяем
        val timeDiff = abs(current.timestamp - prev.timestamp) / 1000
        if (current.type == prev.type && timeDiff < 120) {
            currentCluster.add(current)
        } else {
            // Создаем кластер из накопленных событий
            clusters.add(
                EventCluster(
                    events = currentCluster.toList(),
                    timestamp = currentCluster.first().timestamp,
                    type = currentCluster.first().type
                )
            )
            currentCluster = mutableListOf(current)
        }
    }
    
    // Добавляем последний кластер
    if (currentCluster.isNotEmpty()) {
        clusters.add(
            EventCluster(
                events = currentCluster.toList(),
                timestamp = currentCluster.first().timestamp,
                type = currentCluster.first().type
            )
        )
    }
    
    return clusters
}

private fun groupClustersByTime(clusters: List<EventCluster>): Map<TimeGroup, List<EventCluster>> {
    val calendar = Calendar.getInstance()
    val today = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val yesterday = calendar.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    val weekAgo = calendar.apply { set(Calendar.DAY_OF_MONTH, calendar.get(Calendar.DAY_OF_MONTH) - 6) }.timeInMillis

    return clusters.groupBy { cluster ->
        when {
            cluster.timestamp >= today -> TimeGroup.Today
            cluster.timestamp >= yesterday -> TimeGroup.Yesterday
            cluster.timestamp >= weekAgo -> {
                val dayFormat = SimpleDateFormat("EEEE", Locale("ru"))
                TimeGroup.ThisWeek(dayFormat.format(Date(cluster.timestamp)))
            }
            else -> {
                val dateFormat = SimpleDateFormat("d MMMM", Locale("ru"))
                TimeGroup.Older(dateFormat.format(Date(cluster.timestamp)))
            }
        }
    }.toSortedMap(compareByDescending { it.priority })
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val events by HistoryRepository.getEvents().collectAsState(initial = emptyList())
    
    val clusters = remember(events) { clusterEvents(events) }
    val groupedClusters = remember(clusters) { groupClustersByTime(clusters) }
    
    var showClearDialog by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf<EventType?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }
    
    // 🔥 ДЛЯ ПОЛНОЭКРАННОГО ПРОСМОТРА
    var selectedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var showImageViewer by remember { mutableStateOf(false) }

    // 🔥 ОБНОВЛЕНИЕ ВРЕМЕНИ
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PremiumBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 🔥 HEADER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = PremiumText)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Журнал событий",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = PremiumText
                        )
                        Text(
                            "${events.size} ${getEventCountText(events.size)}",
                            fontSize = 14.sp,
                            color = PremiumTextSec
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(
                        onClick = { showFilterMenu = !showFilterMenu },
                        modifier = Modifier
                            .background(
                                if (filterType != null) PremiumAccent.copy(0.2f) else PremiumCard,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (filterType != null) PremiumAccent.copy(0.3f) else Color.White.copy(0.1f),
                                CircleShape
                            )
                            .size(42.dp)
                    ) {
                        Icon(
                            Icons.Rounded.FilterList,
                            null,
                            tint = if (filterType != null) PremiumAccent else PremiumTextSec
                        )
                    }

                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = events.isNotEmpty(),
                        modifier = Modifier
                            .background(PremiumCard, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            null,
                            tint = if (events.isEmpty())
                                PremiumTextSec.copy(0.3f)
                            else
                                PremiumTextSec
                        )
                    }
                }
            }

            // 🔥 СТАТИСТИКА
            if (events.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val alarmCount = events.count { it.type == "ALARM" }
                    val photoCount = events.count { it.type == "PHOTO" || it.imagePath != null }
                    
                    StatsCard(
                        title = "Тревоги",
                        value = alarmCount.toString(),
                        icon = Icons.Rounded.Warning,
                        color = PremiumDanger,
                        modifier = Modifier.weight(1f)
                    )
                    StatsCard(
                        title = "Фото",
                        value = photoCount.toString(),
                        icon = Icons.Rounded.Image,
                        color = PremiumAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            // 🔥 СПИСОК СОБЫТИЙ
            when {
                events.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(PremiumCard, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.History,
                                    null,
                                    tint = PremiumTextSec.copy(0.3f),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            Text(
                                "История пуста",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = PremiumText
                            )
                            Text(
                                "События будут отображаться здесь",
                                fontSize = 14.sp,
                                color = PremiumTextSec,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> {
                    val filteredGroups = if (filterType != null) {
                        groupedClusters.mapValues { (_, clusters) ->
                            clusters.filter { it.type == filterType.toString() }
                        }.filterValues { it.isNotEmpty() }
                    } else {
                        groupedClusters
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        filteredGroups.forEach { (group, groupClusters) ->
                            item(key = "header_${group.title}") {
                                Text(
                                    text = group.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PremiumTextSec,
                                    letterSpacing = 0.8.sp,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                            
                            items(
                                items = groupClusters,
                                key = { it.timestamp }
                            ) { cluster ->
                                ClusterCard(
                                    cluster = cluster,
                                    currentTime = currentTime,
                                    onImageClick = { images ->
                                        selectedImages = images
                                        showImageViewer = true
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
        }

        // 🔥 МЕНЮ ФИЛЬТРОВ
        AnimatedVisibility(
            visible = showFilterMenu,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.5f))
                    .clickable { showFilterMenu = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 80.dp, end = 20.dp)
                        .width(220.dp)
                        .shadow(16.dp, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = PremiumCard),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        FilterItem(
                            text = "Все события",
                            isSelected = filterType == null,
                            onClick = {
                                filterType = null
                                showFilterMenu = false
                            }
                        )
                        
                        EventType.values().forEach { type ->
                            FilterItem(
                                text = type.label,
                                isSelected = filterType == type,
                                icon = type.icon,
                                iconColor = type.color,
                                onClick = {
                                    filterType = type
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 🔥 ПОЛНОЭКРАННЫЙ ПРОСМОТР ФОТО
    if (showImageViewer && selectedImages.isNotEmpty()) {
        ImageViewerDialog(
            images = selectedImages,
            onDismiss = { showImageViewer = false }
        )
    }

    // 🔥 ДИАЛОГ ОЧИСТКИ
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = PremiumCard,
            icon = {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(PremiumDanger.copy(0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        null,
                        tint = PremiumDanger,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    "Очистить историю?",
                    color = PremiumText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Все события будут удалены без возможности восстановления",
                    color = PremiumTextSec,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            HistoryRepository.clearHistory()
                            showClearDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PremiumDanger
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Очистить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Отмена", color = PremiumTextSec)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

// 🔥 КАРТОЧКА КЛАСТЕРА СОБЫТИЙ
@Composable
fun ClusterCard(
    cluster: EventCluster,
    currentTime: Long,
    onImageClick: (List<String>) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    val eventType = try {
        EventType.valueOf(cluster.type)
    } catch (e: Exception) {
        EventType.SYSTEM
    }

    val timeText = remember(currentTime, cluster.timestamp) {
        val diff = (currentTime - cluster.timestamp) / 1000
        when {
            diff < 60 -> "только что"
            diff < 3600 -> "${diff / 60} мин назад"
            diff < 86400 -> SimpleDateFormat("HH:mm", Locale("ru")).format(Date(cluster.timestamp))
            else -> SimpleDateFormat("HH:mm", Locale("ru")).format(Date(cluster.timestamp))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { isExpanded = !isExpanded }
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = PremiumCard),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(eventType.color.copy(0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            eventType.icon,
                            null,
                            tint = eventType.color,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = cluster.mainEvent.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = PremiumText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 18.sp
                            )
                            
                            // 🔥 БЕЙДЖ КОЛИЧЕСТВА
                            if (cluster.count > 1) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = eventType.color.copy(0.2f)
                                ) {
                                    Text(
                                        text = "×${cluster.count}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = eventType.color,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        
                        Text(
                            text = timeText,
                            fontSize = 12.sp,
                            color = PremiumTextSec,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = PremiumTextSec,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 🔥 ПРЕВЬЮ ФОТО (если есть)
            if (cluster.hasImages && cluster.images.size <= 4 && !isExpanded) {
                Spacer(Modifier.height(12.dp))
                ImagePreviewGrid(
                    images = cluster.images,
                    onClick = { onImageClick(cluster.images) }
                )
            }

            // 🔥 РАЗВЕРНУТЫЙ КОНТЕНТ
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    cluster.events.forEachIndexed { index, event ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                            Divider(color = Color.White.copy(0.05f), thickness = 1.dp)
                            Spacer(Modifier.height(12.dp))
                        }
                        
                        Column {
                            if (event.message.isNotEmpty()) {
                                Text(
                                    text = event.message,
                                    fontSize = 14.sp,
                                    color = PremiumTextSec,
                                    lineHeight = 20.sp
                                )
                            }

                            if (event.imagePath != null) {
                                Spacer(Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onImageClick(listOf(event.imagePath)) }
                                        .shadow(8.dp, RoundedCornerShape(16.dp))
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(File(event.imagePath)),
                                        contentDescription = "Снимок",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    // Индикатор что можно открыть
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(12.dp)
                                            .size(32.dp)
                                            .background(Color.Black.copy(0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.ZoomIn,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 🔥 СЕТКА ПРЕВЬЮ ФОТО
@Composable
fun ImagePreviewGrid(images: List<String>, onClick: () -> Unit) {
    when (images.size) {
        1 -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick)
            ) {
                Image(
                    painter = rememberAsyncImagePainter(File(images[0])),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        2 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                images.forEach { path ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClick)
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(File(path)),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                images.take(3).forEach { path ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClick)
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(File(path)),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        if (path == images[2] && images.size > 3) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+${images.size - 3}",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// 🔥 ПОЛНОЭКРАННЫЙ ПРОСМОТР ФОТО С PAGER
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerDialog(
    images: List<String>,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { images.size })
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // PAGER С ФОТО
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(File(images[page])),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ЗАКРЫТЬ
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(0.5f), CircleShape)
                    .size(48.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // ИНДИКАТОР СТРАНИЦ
            if (images.size > 1) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    shape = RoundedCornerShape(100.dp),
                    color = Color.Black.copy(0.5f)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatsCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(90.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PremiumCard)
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = PremiumTextSec,
                    lineHeight = 13.sp
                )
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = PremiumText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(color.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun FilterItem(
    text: String,
    isSelected: Boolean,
    icon: ImageVector? = null,
    iconColor: Color = PremiumTextSec,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) PremiumAccent.copy(0.15f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(iconColor.copy(0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        null,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                color = if (isSelected) PremiumAccent else PremiumText,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

private fun getEventCountText(count: Int): String {
    val remainder10 = count % 10
    val remainder100 = count % 100
    
    return when {
        remainder100 in 11..14 -> "событий"
        remainder10 == 1 -> "событие"
        remainder10 in 2..4 -> "события"
        else -> "событий"
    }
}
