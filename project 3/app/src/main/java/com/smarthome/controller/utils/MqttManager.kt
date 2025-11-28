package com.smarthome.controller.utils

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.smarthome.controller.data.SystemState
import java.util.UUID

object MqttManager {
    private const val TAG = "MqttManager"
    private var mqttClient: Mqtt3AsyncClient? = null
    private var isConnecting = false
    
    var onConnectionStatusChange: ((Boolean, String) -> Unit)? = null
    
    @Synchronized
    fun sendCommand(context: Context, command: String) {
        try {
            Log.d(TAG, "sendCommand: $command")
            
            val settings = SystemState.mqttSettings
            
            onConnectionStatusChange?.invoke(false, "Отправка...")
            
            if (mqttClient == null) {
                Log.d(TAG, "Creating MQTT client")
                
                mqttClient = MqttClient.builder()
                    .identifier("${settings.clientId}_${UUID.randomUUID()}")
                    .serverHost(settings.server)
                    .serverPort(settings.port)
                    .useMqttVersion3()
                    .buildAsync()
            }
            
            if (mqttClient?.state?.isConnected == true) {
                Log.d(TAG, "Already connected, publishing")
                publishCommand(command)
                return
            }
            
            if (isConnecting) {
                Log.d(TAG, "Connection in progress, skipping")
                return
            }
            
            isConnecting = true
            onConnectionStatusChange?.invoke(false, "Подключение...")
            Log.d(TAG, "Connecting to ${settings.server}:${settings.port}")
            
            mqttClient?.connectWith()
                ?.simpleAuth()
                    ?.username(settings.username)
                    ?.password(settings.password.toByteArray())
                    ?.applySimpleAuth()
                ?.send()
                ?.whenComplete { _, throwable ->
                    isConnecting = false
                    if (throwable != null) {
                        Log.e(TAG, "❌ Connection failed", throwable)
                        onConnectionStatusChange?.invoke(false, "Ошибка подключения")
                    } else {
                        Log.d(TAG, "✅ Connected!")
                        onConnectionStatusChange?.invoke(true, "Подключено")
                        
                        subscribeToTopics()
                        publishCommand(command)
                    }
                }
            
        } catch (e: Exception) {
            isConnecting = false
            Log.e(TAG, "❌ sendCommand error", e)
            onConnectionStatusChange?.invoke(false, "Ошибка: ${e.message}")
        }
    }
    
    private fun subscribeToTopics() {
        try {
            val topicStatus = SystemState.mqttSettings.topicStatus
            val topicTrigger = "user_4bd2b1f5/alarm/trigger" // ← ДОБАВЛЕНО!
            
            // Подписка на СТАТУС
            mqttClient?.subscribeWith()
                ?.topicFilter(topicStatus)
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
                ?.callback { message ->
                    try {
                        val payload = String(message.payloadAsBytes)
                        Log.d(TAG, "📥 Received STATUS: $payload")
                        SystemState.parseStatusFromJson(payload)
                        onConnectionStatusChange?.invoke(true, "Статус обновлён")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing status", e)
                    }
                }
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Subscribe to status failed", throwable)
                    } else {
                        Log.d(TAG, "✅ Subscribed to: $topicStatus")
                    }
                }
            
            // Подписка на СОБЫТИЯ ТРЕВОГИ ← ДОБАВЛЕНО!
            mqttClient?.subscribeWith()
                ?.topicFilter(topicTrigger)
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
                ?.callback { message ->
                    try {
                        val payload = String(message.payloadAsBytes)
                        Log.d(TAG, "🚨 Received ALARM EVENT: $payload")
                        SystemState.handleAlarmEvent(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing alarm event", e)
                    }
                }
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Subscribe to trigger failed", throwable)
                    } else {
                        Log.d(TAG, "✅ Subscribed to: $topicTrigger")
                    }
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ subscribeToTopics error", e)
        }
    }
    
    private fun publishCommand(command: String) {
        try {
            val topic = SystemState.mqttSettings.topicControl
            
            mqttClient?.publishWith()
                ?.topic(topic)
                ?.payload(command.toByteArray())
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE)
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Publish failed", throwable)
                        onConnectionStatusChange?.invoke(false, "Ошибка отправки")
                    } else {
                        Log.d(TAG, "📤 Published: $command to $topic")
                        onConnectionStatusChange?.invoke(true, "Команда отправлена")
                    }
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ publishCommand error", e)
            onConnectionStatusChange?.invoke(false, "Ошибка: ${e.message}")
        }
    }
    
    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient = null
            onConnectionStatusChange?.invoke(false, "Отключено")
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }
}
