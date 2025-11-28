package com.smarthome.controller.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.smarthome.controller.utils.NotificationHelper
import org.json.JSONObject

object SystemState {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    
    var phoneNumber: String = "+79964228371"
        set(value) {
            field = value
            prefs.edit().putString("phone", value).apply()
        }
    
    var autoUpdateEnabled: Boolean = true
        set(value) {
            field = value
            prefs.edit().putBoolean("autoUpdate", value).apply()
        }
    
    var connectionMode: ConnectionMode = ConnectionMode.HYBRID
        set(value) {
            field = value
            prefs.edit().putString("connectionMode", value.name).apply()
        }
    
    var mqttSettings: MqttSettings = MqttSettings()
        set(value) {
            field = value
            prefs.edit()
                .putString("mqttServer", value.server)
                .putInt("mqttPort", value.port)
                .putString("mqttUsername", value.username)
                .putString("mqttPassword", value.password)
                .apply()
        }
    
    var currentStatus: SystemStatus = SystemStatus()
    
    var onStatusUpdate: ((SystemStatus) -> Unit)? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("smart_home", Context.MODE_PRIVATE)
        
        phoneNumber = prefs.getString("phone", "+79964228371") ?: "+79964228371"
        autoUpdateEnabled = prefs.getBoolean("autoUpdate", true)
        
        connectionMode = try {
            ConnectionMode.valueOf(prefs.getString("connectionMode", ConnectionMode.HYBRID.name) ?: ConnectionMode.HYBRID.name)
        } catch (e: Exception) {
            ConnectionMode.HYBRID
        }
        
        mqttSettings = MqttSettings(
            server = prefs.getString("mqttServer", "srv2.clusterfly.ru") ?: "srv2.clusterfly.ru",
            port = prefs.getInt("mqttPort", 9991),
            username = prefs.getString("mqttUsername", "user_4bd2b1f5") ?: "user_4bd2b1f5",
            password = prefs.getString("mqttPassword", "FnoQuMvkcV1ej") ?: "FnoQuMvkcV1ej"
        )
        
        NotificationHelper.createNotificationChannel(context)
    }
    
    fun updateStatus(status: SystemStatus) {
        currentStatus = status
        onStatusUpdate?.invoke(status)
    }
    
    fun parseStatusFromJson(json: String) {
        try {
            val obj = JSONObject(json)
            
            val newStatus = SystemStatus(
                alarmEnabled = obj.optBoolean("armed", currentStatus.alarmEnabled),
                soundLevel = obj.optInt("threshold", currentStatus.soundLevel),
                pirStatus = if (obj.optBoolean("pir", false)) "MOTION" else "CLEAR",
                systemLocked = obj.optBoolean("locked", currentStatus.systemLocked),
                isAlarm = false,  // Статус НЕ содержит alarm!
                lastUpdate = System.currentTimeMillis()
            )
            
            updateStatus(newStatus)
            
        } catch (e: Exception) {
            Log.e("SystemState", "Error parsing JSON", e)
        }
    }
    
    // Обработка события тревоги
    fun handleAlarmEvent(json: String) {
        try {
            val obj = JSONObject(json)
            val type = obj.optString("type", "")
            
            if (type == "alarm" && ::appContext.isInitialized) {
                // Показываем тревогу на экране
                val alarmStatus = currentStatus.copy(isAlarm = true)
                updateStatus(alarmStatus)
                
                // Отправляем уведомление
                NotificationHelper.sendAlarmNotification(
                    appContext,
                    "Сработала сигнализация! Движение+Звук обнаружены."
                )
                Log.d("SystemState", "🚨 Alarm event handled!")
            }
        } catch (e: Exception) {
            Log.e("SystemState", "Error parsing alarm event", e)
        }
    }
    
    // Сброс тревоги ← ДОБАВЛЕНО!
    fun clearAlarm() {
        if (currentStatus.isAlarm) {
            val clearedStatus = currentStatus.copy(isAlarm = false)
            updateStatus(clearedStatus)
            Log.d("SystemState", "Alarm cleared")
        }
    }
}
