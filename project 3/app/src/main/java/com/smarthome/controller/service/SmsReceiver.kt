package com.smarthome.controller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.data.SystemStatus

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            messages.forEach { message ->
                val sender = message.originatingAddress ?: ""
                val body = message.messageBody ?: ""
                
                Log.d(TAG, "=== SMS ПОЛУЧЕНО ===")
                Log.d(TAG, "От: $sender")
                Log.d(TAG, "Текст: [$body]")
                
                val normalizedSender = normalizePhone(sender)
                val normalizedSystemPhone = normalizePhone(SystemState.phoneNumber)
                
                if (normalizedSender == normalizedSystemPhone) {
                    processSmsFromArduino(context, body)
                } else {
                    Log.d(TAG, "Проигнорировано: не тот отправитель")
                }
            }
        }
    }
    
    private fun normalizePhone(phone: String): String {
        var normalized = phone.replace(Regex("[\\s\\-()]"), "")
        if (normalized.startsWith("+7")) {
            normalized = "8" + normalized.substring(2)
        } else if (normalized.startsWith("7") && normalized.length == 11) {
            normalized = "8" + normalized.substring(1)
        }
        return normalized
    }
    
    private fun processSmsFromArduino(context: Context, message: String) {
        val trimmedMessage = message.trim()
        
        // СТРОГАЯ ПРОВЕРКА: Только если НАЧИНАЕТСЯ с "ALARM!" (с восклицательным знаком!)
        if (trimmedMessage.startsWith("ALARM!", ignoreCase = false)) {
            Log.d(TAG, ">>> ТРЕВОГА ОБНАРУЖЕНА <<<")
            SystemState.currentStatus = SystemState.currentStatus.copy(
                isAlarm = true,
                alarmEnabled = true
            )
            SystemState.onStatusUpdate?.invoke(SystemState.currentStatus)
            SmsMonitorService.showAlarmNotification(context, message)
            return
        }
        
        // Подтверждение ВЫКЛЮЧЕНИЯ охраны: "OK: alarm DISABLED"
        if (trimmedMessage.startsWith("OK:", ignoreCase = true) && 
            trimmedMessage.contains("DISABLED", ignoreCase = true)) {
            Log.d(TAG, ">>> Охрана ВЫКЛЮЧЕНА (подтверждено) <<<")
            SystemState.currentStatus = SystemState.currentStatus.copy(
                alarmEnabled = false,
                isAlarm = false,
                systemLocked = false
            )
            SystemState.onStatusUpdate?.invoke(SystemState.currentStatus)
            return
        }
        
        // Подтверждение ВКЛЮЧЕНИЯ охраны: "OK: alarm ENABLED"
        if (trimmedMessage.startsWith("OK:", ignoreCase = true) && 
            trimmedMessage.contains("ENABLED", ignoreCase = true)) {
            Log.d(TAG, ">>> Охрана ВКЛЮЧЕНА (подтверждено) <<<")
            SystemState.currentStatus = SystemState.currentStatus.copy(
                alarmEnabled = true,
                isAlarm = false
            )
            SystemState.onStatusUpdate?.invoke(SystemState.currentStatus)
            return
        }
        
        // Полный статус от Arduino: "STATUS:\nAlarm:ENABLED\nLocked:NO\n..."
        if (trimmedMessage.contains("STATUS:", ignoreCase = true) ||
            (trimmedMessage.contains("Alarm:", ignoreCase = true) && 
             trimmedMessage.contains("Locked:", ignoreCase = true))) {
            Log.d(TAG, ">>> Получен СТАТУС системы <<<")
            val status = parseStatus(trimmedMessage)
            SystemState.currentStatus = status
            SystemState.onStatusUpdate?.invoke(status)
            return
        }
        
        // Неизвестная команда от Arduino
        if (trimmedMessage.startsWith("Unknown command", ignoreCase = true)) {
            Log.d(TAG, "Arduino не распознал команду")
            return
        }
        
        // Всё остальное игнорируем
        Log.d(TAG, "Неизвестное сообщение, игнорируем")
    }
    
    private fun parseStatus(message: String): SystemStatus {
        return try {
            // Парсим статус Arduino
            val alarmEnabled = message.contains("Alarm:ENABLED", ignoreCase = true)
            val locked = message.contains("Locked:YES", ignoreCase = true)
            val pirMotion = message.contains("PIR:MOTION", ignoreCase = true)
            
            val soundRegex = "Sound:(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            val soundMatch = soundRegex.find(message)
            val soundLevel = soundMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            val status = SystemStatus(
                alarmEnabled = alarmEnabled,
                systemLocked = locked,
                pirStatus = if (pirMotion) "MOTION" else "NO_MOTION",
                soundLevel = soundLevel,
                lastUpdate = System.currentTimeMillis(),
                isAlarm = false  // Статус НЕ содержит информацию о тревоге
            )
            
            val pirText = if (pirMotion) "MOTION" else "NO"
            Log.d(TAG, "Статус распарсен: охрана=$alarmEnabled, блокировка=$locked, PIR=$pirText, звук=$soundLevel")
            status
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга: ${e.message}")
            SystemState.currentStatus
        }
    }
}
