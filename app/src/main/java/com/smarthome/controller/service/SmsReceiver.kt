package com.smarthome.controller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.smarthome.controller.data.SystemState

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        
        try {
            val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } else {
                val pdus = intent.extras?.get("pdus") as? Array<*>
                pdus?.mapNotNull { pduObj -> // Исправлено имя переменной
                    SmsMessage.createFromPdu(pduObj as ByteArray)
                }?.toTypedArray()
            }
            
            messages?.forEach { message ->
                val sender = message.originatingAddress ?: ""
                val body = message.messageBody ?: ""
                
                Log.d(TAG, "📩 SMS от: $sender")
                Log.d(TAG, "📩 Текст: $body")
                
                // 1. Проверяем НОВЫЙ формат (Alarm:ON, Motion:YES, Sound:26.3)
                // С переносами строк \n
                if (body.contains("Alarm:", ignoreCase = true) || 
                    body.contains("Motion:", ignoreCase = true)) {
                    Log.d(TAG, "📊 Парсинг НОВОГО формата (YES/NO)")
                    parseRealStatus(body, context)
                }
                
                // 2. Проверяем СТАРЫЙ формат и команды
                else when {
                    // Подтверждения команд
                    body.contains("OK: Alarm ON", ignoreCase = true) ||
                    body.contains("Alarm ON", ignoreCase = true) -> {
                        Log.d(TAG, "✅ Сигнализация ВКЛЮЧЕНА")
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                alarmEnabled = true,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )
                    }
                    
                    body.contains("OK: Alarm OFF", ignoreCase = true) ||
                    body.contains("Alarm OFF", ignoreCase = true) -> {
                        Log.d(TAG, "✅ Сигнализация ВЫКЛЮЧЕНА")
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                alarmEnabled = false,
                                isAlarm = false,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )
                        // Снимаем уведомление тревоги
                        context?.let { SmsMonitorService.clearAlarmNotification(it) }
                    }
                    
                    body.contains("OK: Locked", ignoreCase = true) ||
                    body.contains("Locked", ignoreCase = true) -> {
                        Log.d(TAG, "✅ Система ЗАБЛОКИРОВАНА")
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                systemLocked = true,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )
                    }
                    
                    body.contains("OK: Unlocked", ignoreCase = true) ||
                    body.contains("Unlocked", ignoreCase = true) -> {
                        Log.d(TAG, "✅ Система РАЗБЛОКИРОВАНА")
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                systemLocked = false,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )
                    }
                    
                    // Тревога
                    body.contains("ALARM!", ignoreCase = true) ||
                    body.contains("ТРЕВОГА", ignoreCase = true) -> {
                        Log.d(TAG, "🚨 ТРЕВОГА из SMS")

                        val alreadyAlarming = SystemState.currentStatus.isAlarm

                        // 1. Обновляем статус в приложении
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                isAlarm = true,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )

                        // 2. Показываем уведомление только если тревога ещё не была (избегаем дубля от MQTT)
                        if (!alreadyAlarming) {
                            context?.let { ctx ->
                                SmsMonitorService.showAlarmNotification(ctx, body)
                            }
                        } else {
                            Log.d(TAG, "⏭️ Тревога уже активна (MQTT), пропускаем дубль уведомления")
                        }
                    }
                    
                    // Полный статус СТАРЫЙ (формат: Armed:1,Locked:0,PIR:0,Sound:45)
                    body.contains("Armed:", ignoreCase = true) -> {
                        Log.d(TAG, "📊 Парсинг СТАРОГО статуса из SMS")
                        parseStatusFromSms(body, context)
                    }
                    
                    else -> {
                        // Если не попали ни в одну ветку выше, но сообщение пришло
                        Log.d(TAG, "ℹ️ SMS обработано (возможно, новый формат)")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обработки SMS: ${e.message}", e)
        }
    }
    
    // Парсер для формата:
    // Alarm:ON
    // Lock:NO
    // Motion:YES
    // Sound:26.3
    private fun parseRealStatus(body: String, context: Context?) {
        try {
            // Разбиваем по переносам строк (\n) или \r
            val lines = body.split("\n", "\r")
            
            var armed = SystemState.currentStatus.alarmEnabled
            var locked = SystemState.currentStatus.systemLocked
            var pirStatus = SystemState.currentStatus.pirStatus
            var soundLevel = SystemState.currentStatus.soundLevel
            
            lines.forEach { line ->
                // Разделяем по двоеточию
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim().uppercase() // YES, NO, ON, OFF
                    
                    when (key) {
                        "alarm" -> armed = (value == "ON" || value == "YES" || value == "1")
                        "lock" -> locked = (value == "ON" || value == "YES" || value == "1")
                        "motion" -> pirStatus = if (value == "YES" || value == "ON" || value == "MOTION") "MOTION" else "CLEAR"
                        "sound" -> {
                            // "26.3" -> 26
                            // Удаляем лишнее, парсим Double -> Int
                            val cleanVal = parts[1].trim()
                            val doubleVal = cleanVal.toDoubleOrNull()
                            if (doubleVal != null) {
                                soundLevel = doubleVal.toInt()
                            }
                        }
                    }
                }
            }
            
            SystemState.updateStatus(
                SystemState.currentStatus.copy(
                    alarmEnabled = armed,
                    systemLocked = locked,
                    pirStatus = pirStatus,
                    soundLevel = soundLevel,
                    isAlarm = false, // Сброс тревоги
                    lastUpdate = System.currentTimeMillis()
                )
            )
            
            // Убираем уведомление тревоги, так как получили свежий статус
            context?.let { SmsMonitorService.clearAlarmNotification(it) }
            
            Log.d(TAG, "✅ Parsed Real Status: Armed=$armed, Motion=$pirStatus, Sound=$soundLevel")
            
        } catch (e: Exception) {
            Log.e(TAG, "Parse Real Error: ${e.message}", e)
        }
    }
    
    // Старый парсер для: Armed:1,Locked:0,PIR:0,Sound:45
    private fun parseStatusFromSms(body: String, context: Context?) {
        try {
            val parts = body.split(",")
            var armed = false
            var locked = false
            var pirStatus = "CLEAR"
            var soundLevel = 0
            
            parts.forEach { part ->
                val keyValue = part.split(":")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    
                    when {
                        key.equals("Armed", ignoreCase = true) -> {
                            armed = value == "1" || value.equals("true", ignoreCase = true)
                        }
                        key.equals("Locked", ignoreCase = true) -> {
                            locked = value == "1" || value.equals("true", ignoreCase = true)
                        }
                        key.equals("PIR", ignoreCase = true) -> {
                            pirStatus = if (value == "1") "MOTION" else "CLEAR"
                        }
                        key.equals("Sound", ignoreCase = true) -> {
                            soundLevel = value.toIntOrNull() ?: 0
                        }
                    }
                }
            }
            
            Log.d(TAG, "📊 Статус (Legacy): armed=$armed, locked=$locked, pir=$pirStatus, sound=$soundLevel")
            
            SystemState.updateStatus(
                SystemState.currentStatus.copy(
                    alarmEnabled = armed,
                    systemLocked = locked,
                    pirStatus = pirStatus,
                    soundLevel = soundLevel,
                    isAlarm = false,
                    lastUpdate = System.currentTimeMillis()
                )
            )
            
            // Тоже снимаем уведомление
            context?.let { SmsMonitorService.clearAlarmNotification(it) }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга статуса: ${e.message}", e)
        }
    }
}
