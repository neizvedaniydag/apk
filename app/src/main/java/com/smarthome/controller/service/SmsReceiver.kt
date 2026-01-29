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
                pdus?.mapNotNull { pdu ->
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }?.toTypedArray()
            }
            
            messages?.forEach { message ->
                val sender = message.originatingAddress ?: ""
                val body = message.messageBody ?: ""
                
                Log.d(TAG, "📩 SMS от: $sender")
                Log.d(TAG, "📩 Текст: $body")
                
                // ✅ ПРОВЕРЯЕМ ВСЕ ВАРИАНТЫ ОТВЕТОВ ОТ ESP32
                when {
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
                        Log.d(TAG, "🚨 ТРЕВОГА!")
                        SystemState.updateStatus(
                            SystemState.currentStatus.copy(
                                isAlarm = true,
                                lastUpdate = System.currentTimeMillis()
                            )
                        )
                    }
                    
                    // Полный статус (формат: Armed:1,Locked:0,PIR:0,Sound:45)
                    body.contains("Armed:", ignoreCase = true) -> {
                        Log.d(TAG, "📊 Парсинг статуса из SMS")
                        parseStatusFromSms(body)
                    }
                    
                    else -> {
                        Log.d(TAG, "ℹ️ Неизвестный формат SMS")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обработки SMS: ${e.message}", e)
        }
    }
    
    private fun parseStatusFromSms(body: String) {
        try {
            // Формат: Armed:1,Locked:0,PIR:0,Sound:45
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
            
            Log.d(TAG, "📊 Статус: armed=$armed, locked=$locked, pir=$pirStatus, sound=$soundLevel")
            
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
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга статуса: ${e.message}", e)
        }
    }
}
