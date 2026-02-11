package com.smarthome.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smarthome.controller.data.HistoryRepository
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.ui.HistoryScreen
import com.smarthome.controller.ui.HomeScreen
import com.smarthome.controller.ui.SettingsScreen
import com.smarthome.controller.ui.VideoStreamScreen
import com.smarthome.controller.ui.theme.SmartHomeTheme
import com.smarthome.controller.utils.CrashHandler
import com.smarthome.controller.utils.MqttManager

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Говорим приложению рисовать на весь экран (под статус баром)
        enableEdgeToEdge()
        
        CrashHandler.install(this)
        SystemState.init(this)
        HistoryRepository.init(this)
        MqttManager.startAutoConnect(this)

        setContent {
            SmartHomeTheme {
                var currentScreen by remember { mutableStateOf("home") }
                
                // Цвет фона общий для приложения
                val backgroundColor = if (currentScreen == "video") Color.Black else Color(0xFF1A1F2C)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = backgroundColor
                ) {
                    // 2. ЛОГИКА ОТСТУПОВ (Самое важное место)
                    // Если мы в видео -> отступов НЕТ (Modifier.fillMaxSize)
                    // Если любой другой экран -> ОТСТУП ЕСТЬ (Modifier.safeDrawingPadding)
                    val containerModifier = if (currentScreen == "video") {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding() // <--- ВОТ ЭТА МАГИЯ. Она сама найдет камеру и отодвинет контент.
                    }

                    Box(modifier = containerModifier) {
                        Crossfade(
                            targetState = currentScreen,
                            animationSpec = tween(300),
                            label = "ScreenNavigation"
                        ) { screen ->
                            when (screen) {
                                "home" -> HomeScreen(
                                    onNavigateToSettings = { currentScreen = "settings" },
                                    onNavigateToVideo = { currentScreen = "video" },
                                    onNavigateToHistory = { currentScreen = "history" }
                                )
                                "settings" -> SettingsScreen(
                                    onBack = { currentScreen = "home" }
                                )
                                "video" -> VideoStreamScreen(
                                    onBack = { currentScreen = "home" }
                                )
                                "history" -> HistoryScreen(
                                    onBack = { currentScreen = "home" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.stopAutoConnect()
    }
}
