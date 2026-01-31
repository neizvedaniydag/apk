package com.smarthome.controller.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.smarthome.controller.data.HistoryEvent
import com.smarthome.controller.data.HistoryRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// СОВРЕМЕННАЯ ЦВЕТОВАЯ СХЕМА (Material Design 3)
private object AppColors {
    val Background = Color(0xFF0F1419)
    val Surface = Color(0xFF1C2127)
    val SurfaceVariant = Color(0xFF262D35)
    val Primary = Color(0xFF3B82F6)
    val PrimaryVariant = Color(0xFF2563EB)
    val OnPrimary = Color.White
    val OnSurface = Color(0xFFE8E8E8)
    val OnSurfaceVariant = Color(0xFF9CA3AF)
    val Error = Color(0xFFEF4444)
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Divider = Color(0xFF2D3748)
}

// ТИПЫ СОБЫТИЙ С ИКОНКАМИ
enum class EventType(val icon: ImageVector, val color: Color) {
    ALARM(Icons.Rounded.Warning, AppColors.Error),
    PHOTO(Icons.Rounded.Image, AppColors.Primary),
    MOTION(Icons.Rounded.Sensors, AppColors.Warning),
    DOOR(Icons.Rounded.DoorFront, AppColors.Success),
    SYSTEM(Icons.Rounded.Settings, AppColors.OnSurfaceVariant)
}

// ГРУППИРОВКА СОБЫТИЙ ПО ВРЕМЕНИ
sealed class TimeGroup(val title: String) {
    data object Today : TimeGroup("Сегодня")
    data object Yesterday : TimeGroup("Вчера")
    data class ThisWeek(val date: String) : TimeGroup(date)
    data class Older(val date: String) : TimeGroup(date)
}

// ФУНКЦИЯ ГРУППИРОВКИ
private fun groupEventsByTime(events: List<HistoryEvent>): Map<TimeGroup, List<HistoryEvent>> {
    val calendar = Calendar.getInstance()
    val today = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val yesterday = calendar.apply {
        add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    val weekAgo = calendar.apply {
        set(Calendar.DAY_OF_MONTH, calendar.get(Calendar.DAY_OF_MONTH) - 6)
    }.timeInMillis

    return events.groupBy { event ->
        when {
            event.timestamp >= today -> TimeGroup.Today
            event.timestamp >= yesterday -> TimeGroup.Yesterday
            event.timestamp >= weekAgo -> {
                val dayFormat = SimpleDateFormat("EEEE", Locale("ru"))
                TimeGroup.ThisWeek(dayFormat.format(Date(event.timestamp)))
            }
            else -> {
                val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
                TimeGroup.Older(dateFormat.format(Date(event.timestamp)))
            }
        }
    }.toSortedMap(compareByDescending {
        when (it) {
            is TimeGroup.Today -> 4
            is TimeGroup.Yesterday -> 3
            is TimeGroup.ThisWeek -> 2
            is TimeGroup.Older -> 1
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val events by HistoryRepository.getEvents().collectAsState(initial = emptyList())
    val groupedEvents = remember(events) { groupEventsByTime(events) }
    
    var showClearDialog by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf<EventType?>(null) }
    var isFilterExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Журнал событий",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.OnSurface
                        )
                        Text(
                            "${events.size} ${getEventCountText(events.size)}",
                            fontSize = 13.sp,
                            color = AppColors.OnSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Назад",
                            tint = AppColors.OnSurface
                        )
                    }
                },
                actions = {
                    // ФИЛЬТР
                    IconButton(onClick = { isFilterExpanded = !isFilterExpanded }) {
                        Icon(
                            Icons.Rounded.FilterList,
                            contentDescription = "Фильтр",
                            tint = if (filterType != null) AppColors.Primary else AppColors.OnSurfaceVariant
                        )
                    }
                    
                    // ОЧИСТКА
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = events.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Очистить",
                            tint = if (events.isEmpty()) 
                                AppColors.OnSurfaceVariant.copy(alpha = 0.3f) 
                            else 
                                AppColors.OnSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                events.isEmpty() -> EmptyState()
                else -> {
                    val filteredGroups = if (filterType != null) {
                        groupedEvents.mapValues { (_, events) ->
                            events.filter { it.type == filterType.toString() }
                        }.filterValues { it.isNotEmpty() }
                    } else {
                        groupedEvents
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filteredGroups.forEach { (group, groupEvents) ->
                            // ЗАГОЛОВОК ГРУППЫ
                            item(key = "header_${group.title}") {
                                TimeGroupHeader(group.title)
                            }
                            
                            // СОБЫТИЯ В ГРУППЕ
                            items(
                                items = groupEvents,
                                key = { it.timestamp }
                            ) { event ->
                                EventCard(
                                    event = event,
                                    modifier = Modifier.animateItemPlacement()
                                )
                            }
                        }
                    }
                }
            }

            // ФИЛЬТР DROPDOWN
            if (isFilterExpanded) {
                FilterMenu(
                    currentFilter = filterType,
                    onFilterSelected = { 
                        filterType = it
                        isFilterExpanded = false
                    },
                    onDismiss = { isFilterExpanded = false }
                )
            }
        }
    }

    // ДИАЛОГ ПОДТВЕРЖДЕНИЯ ОЧИСТКИ
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = AppColors.Error
                )
            },
            title = {
                Text(
                    "Очистить историю?",
                    color = AppColors.OnSurface
                )
            },
            text = {
                Text(
                    "Все события будут удалены без возможности восстановления",
                    color = AppColors.OnSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            HistoryRepository.clearHistory()
                            showClearDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = AppColors.Error
                    )
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена", color = AppColors.OnSurfaceVariant)
                }
            },
            containerColor = AppColors.Surface
        )
    }
}

@Composable
fun TimeGroupHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Primary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Divider(
            modifier = Modifier.weight(1f),
            color = AppColors.Divider,
            thickness = 1.dp
        )
    }
}

@Composable
fun EventCard(
    event: HistoryEvent,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val eventType = try {
        EventType.valueOf(event.type)
    } catch (e: Exception) {
        EventType.SYSTEM
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(
            containerColor = AppColors.SurfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                    // ИКОНКА ТИПА
                    Surface(
                        shape = CircleShape,
                        color = eventType.color.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                eventType.icon,
                                contentDescription = null,
                                tint = eventType.color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = event.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.OnSurface,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale("ru"))
                                .format(Date(event.timestamp)),
                            fontSize = 13.sp,
                            color = AppColors.OnSurfaceVariant
                        )
                    }
                }

                // ИНДИКАТОР РАСКРЫТИЯ
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = AppColors.OnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ДЕТАЛИ (РАСКРЫВАЮЩИЕСЯ)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    if (event.message.isNotEmpty()) {
                        Text(
                            text = event.message,
                            fontSize = 14.sp,
                            color = AppColors.OnSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }

                    // ИЗОБРАЖЕНИЕ
                    if (event.imagePath != null) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(File(event.imagePath)),
                                contentDescription = "Снимок",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = null,
            tint = AppColors.OnSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(80.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "История пуста",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.OnSurfaceVariant
        )
        Text(
            "События будут отображаться здесь",
            fontSize = 14.sp,
            color = AppColors.OnSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun FilterMenu(
    currentFilter: EventType?,
    onFilterSelected: (EventType?) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        color = Color.Black.copy(alpha = 0.5f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(200.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                elevation = CardDefaults.cardElevation(8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    FilterItem("Все", currentFilter == null) {
                        onFilterSelected(null)
                    }
                    
                    EventType.values().forEach { type ->
                        FilterItem(
                            text = type.name,
                            isSelected = currentFilter == type,
                            icon = type.icon,
                            iconColor = type.color
                        ) {
                            onFilterSelected(type)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterItem(
    text: String,
    isSelected: Boolean,
    icon: ImageVector? = null,
    iconColor: Color = AppColors.OnSurfaceVariant,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) AppColors.Primary.copy(alpha = 0.15f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                color = if (isSelected) AppColors.Primary else AppColors.OnSurface,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

// ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ ДЛЯ СКЛОНЕНИЯ
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
