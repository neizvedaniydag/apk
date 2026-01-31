package com.smarthome.controller.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.smarthome.controller.data.HistoryEvent
import com.smarthome.controller.data.HistoryRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// Цвета
private val PremiumBg = Color(0xFF1A1F2C)
private val PremiumCard = Color(0xFF242B3D)
private val PremiumText = Color(0xFFFFFFFF)
private val PremiumTextSec = Color(0xFF94A3B8)
private val PremiumDanger = Color(0xFFEF4444)

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Подписка на данные из базы в реальном времени
    val events by HistoryRepository.getEvents().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier.fillMaxSize().background(PremiumBg)
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PremiumCard.copy(alpha = 0.5f))
                .padding(16.dp)
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Журнал событий",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // Кнопка очистки
            IconButton(onClick = {
                scope.launch { HistoryRepository.clearHistory() }
            }) {
                Icon(Icons.Rounded.Delete, null, tint = PremiumTextSec)
            }
        }

        // --- СПИСОК ---
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("История пуста", color = PremiumTextSec)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(events) { event ->
                    HistoryCard(event)
                }
            }
        }
    }
}

@Composable
fun HistoryCard(event: HistoryEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PremiumCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (event.type == "ALARM") Icons.Rounded.Warning else Icons.Rounded.Image,
                        null,
                        tint = if (event.type == "ALARM") PremiumDanger else PremiumTextSec,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        event.title,
                        color = PremiumText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Text(
                    SimpleDateFormat("dd MMM HH:mm", Locale("ru")).format(Date(event.timestamp)),
                    color = PremiumTextSec,
                    fontSize = 12.sp
                )
            }
            
            if (event.message.isNotEmpty()) {
                Text(
                    event.message,
                    color = PremiumTextSec,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // --- ЕСЛИ ЕСТЬ ФОТО ---
            if (event.imagePath != null) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(File(event.imagePath)),
                        contentDescription = "Snapshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
