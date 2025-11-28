package com.smarthome.controller.utils

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast

object SmsSender {
    private const val TAG = "SmsSender"
    
    fun sendSms(context: Context, phoneNumber: String, message: String) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            
            Log.d(TAG, "SMS отправлено: $message")
            Toast.makeText(context, "Отправлено: $message", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка: ${e.message}")
            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    fun sendCommand(context: Context, command: String) {
        val phoneNumber = com.smarthome.controller.data.SystemState.phoneNumber
        sendSms(context, phoneNumber, command)
    }
}
