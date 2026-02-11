package com.smarthome.controller.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.smarthome.controller.data.SystemState

object SmsSender {

    fun sendCommand(context: Context, command: String) {
        // 1. Берем номер из настроек
        var rawNumber = SystemState.phoneNumber
        
        // 2. Если пусто — пробуем дефолтный (замени на свой, если нужно)
        if (rawNumber.isBlank()) {
            // rawNumber = "+79000000000" // Раскомментируй и впиши номер, если хочешь хардкод
        }

        // 3. ОЧИСТКА: Удаляем все кроме цифр и плюса (убирает пробелы, скобки)
        val phoneNumber = rawNumber.replace(Regex("[^0-9+]"), "")

        // 4. Проверка
        if (phoneNumber.length < 5) {
            Toast.makeText(context, "❌ Ошибка: Номер телефона не задан или слишком короткий!", Toast.LENGTH_LONG).show()
            Log.e("SmsSender", "Пытались отправить на: '$rawNumber' (очищенный: '$phoneNumber')")
            return
        }

        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

            // Отправка
            smsManager.sendTextMessage(phoneNumber, null, command, null, null)
            Toast.makeText(context, "SMS на $phoneNumber: $command", Toast.LENGTH_SHORT).show()
            
        } catch (e: SecurityException) {
            Log.e("SmsSender", "Нет прав, открываем приложение", e)
            sendSmsIntent(context, phoneNumber, command)
        } catch (e: Exception) {
            Log.e("SmsSender", "Ошибка отправки", e)
            Toast.makeText(context, "Ошибка SMS: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSmsIntent(context: Context, phone: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть SMS", Toast.LENGTH_SHORT).show()
        }
    }
}
