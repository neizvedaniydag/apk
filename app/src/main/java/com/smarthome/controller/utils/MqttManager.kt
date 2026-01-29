package com.smarthome.controller.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.data.ConnectionMode
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object MqttManager {
    private const val TAG = "MqttManager"
    private var mqttClient: Mqtt3AsyncClient? = null
    private var isConnecting = false
    private var reconnectScheduler: ScheduledExecutorService? = null
    private var isAutoConnectEnabled = false
    private var appContext: Context? = null
    
    var onConnectionStatusChange: ((Boolean, String) -> Unit)? = null
    
    private fun hasInternetConnection(context: Context): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                val network = connectivityManager.activeNetwork
                if (network == null) {
                    return false
                }
                
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities == null) {
                    return false
                }
                
                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet connection", e)
        }
        return false
    }
    
    private fun shouldUseMqtt(): Boolean {
        return when (SystemState.connectionMode) {
            ConnectionMode.MQTT_ONLY -> true
            ConnectionMode.SMS_ONLY -> false
            ConnectionMode.HYBRID -> true
        }
    }
    
    fun startAutoConnect(context: Context) {
        if (isAutoConnectEnabled) {
            Log.d(TAG, "Auto-connect already enabled")
            return
        }
        
        appContext = context.applicationContext
        isAutoConnectEnabled = true
        Log.d(TAG, "🔄 Starting auto-connect...")
        
        if (!shouldUseMqtt()) {
            Log.d(TAG, "📴 SMS Only mode - MQTT disabled")
            onConnectionStatusChange?.invoke(false, "Режим SMS")
            return
        }
        
        if (hasInternetConnection(context)) {
            connectAndSubscribe(context)
        } else {
            Log.d(TAG, "⚠️ No internet, skipping initial connect")
            onConnectionStatusChange?.invoke(false, "Нет интернета")
        }
        
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
        reconnectScheduler?.scheduleWithFixedDelay({
            try {
                val ctx = appContext
                if (ctx == null) {
                    return@scheduleWithFixedDelay
                }
                
                if (!shouldUseMqtt()) {
                    if (mqttClient?.state?.isConnected == true) {
                        Log.d(TAG, "📴 SMS mode enabled, disconnecting MQTT...")
                        disconnect()
                    }
                    return@scheduleWithFixedDelay
                }
                
                if (!hasInternetConnection(ctx)) {
                    if (mqttClient?.state?.isConnected == true) {
                        Log.d(TAG, "📴 Internet lost, disconnecting...")
                        disconnect()
                    }
                    return@scheduleWithFixedDelay
                }
                
                if (mqttClient?.state?.isConnected != true && !isConnecting) {
                    Log.d(TAG, "🔄 Auto-reconnect triggered")
                    connectAndSubscribe(ctx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-reconnect error", e)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }
    
    fun stopAutoConnect() {
        isAutoConnectEnabled = false
        reconnectScheduler?.shutdown()
        reconnectScheduler = null
        disconnect()
        appContext = null
        Log.d(TAG, "Auto-connect stopped")
    }
    
    fun onConnectionModeChanged() {
        val ctx = appContext
        if (ctx == null) {
            Log.d(TAG, "⚠️ Context is null, skipping mode change")
            return
        }
        
        if (!shouldUseMqtt()) {
            Log.d(TAG, "📴 Switched to SMS mode - disconnecting MQTT")
            disconnect()
            onConnectionStatusChange?.invoke(false, "Режим SMS")
        } else {
            if (hasInternetConnection(ctx) && mqttClient?.state?.isConnected != true && !isConnecting) {
                Log.d(TAG, "📡 Switched to MQTT mode - connecting")
                connectAndSubscribe(ctx)
            }
        }
    }
    
    @Synchronized
    private fun connectAndSubscribe(context: Context) {
        try {
            if (!shouldUseMqtt()) {
                Log.d(TAG, "📴 SMS Only mode - aborting MQTT connect")
                onConnectionStatusChange?.invoke(false, "Режим SMS")
                return
            }
            
            if (!hasInternetConnection(context)) {
                Log.d(TAG, "⚠️ No internet, aborting connect")
                onConnectionStatusChange?.invoke(false, "Нет интернета")
                return
            }
            
            val settings = SystemState.mqttSettings
            
            if (isConnecting) {
                Log.d(TAG, "Connection in progress, skipping")
                return
            }
            
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
                Log.d(TAG, "Already connected")
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
                ?.keepAlive(60)
                ?.send()
                ?.whenComplete { _, throwable ->
                    isConnecting = false
                    if (throwable != null) {
                        Log.e(TAG, "❌ Connection failed: ${throwable.message}")
                        onConnectionStatusChange?.invoke(false, "Ошибка подключения")
                        mqttClient = null
                    } else {
                        Log.d(TAG, "✅ Connected!")
                        onConnectionStatusChange?.invoke(true, "Подключено")
                        subscribeToTopics()
                    }
                }
            
        } catch (e: Exception) {
            isConnecting = false
            Log.e(TAG, "❌ connectAndSubscribe error: ${e.message}", e)
            onConnectionStatusChange?.invoke(false, "Ошибка: ${e.message}")
            mqttClient = null
        }
    }
    
    @Synchronized
    fun sendCommand(context: Context, command: String) {
        try {
            Log.d(TAG, "sendCommand: $command")
            
            if (!shouldUseMqtt()) {
                Log.d(TAG, "📴 SMS Only mode - command ignored")
                onConnectionStatusChange?.invoke(false, "Режим SMS (используйте SMS)")
                return
            }
            
            if (!hasInternetConnection(context)) {
                Log.d(TAG, "⚠️ No internet for command")
                onConnectionStatusChange?.invoke(false, "Нет интернета (используйте SMS)")
                return
            }
            
            onConnectionStatusChange?.invoke(false, "Отправка...")
            
            if (mqttClient?.state?.isConnected == true) {
                Log.d(TAG, "Already connected, publishing")
                publishCommand(command)
                return
            }
            
            if (!isConnecting) {
                connectAndSubscribe(context)
                
                Thread {
                    var attempts = 0
                    while (attempts < 10 && mqttClient?.state?.isConnected != true) {
                        Thread.sleep(500)
                        attempts++
                    }
                    
                    if (mqttClient?.state?.isConnected == true) {
                        publishCommand(command)
                    } else {
                        Log.e(TAG, "Failed to connect for command")
                        onConnectionStatusChange?.invoke(false, "Не удалось подключиться")
                    }
                }.start()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendCommand error: ${e.message}", e)
            onConnectionStatusChange?.invoke(false, "Ошибка: ${e.message}")
        }
    }
    
    private fun subscribeToTopics() {
        try {
            val topicStatus = SystemState.mqttSettings.topicStatus
            val topicTrigger = "user_4bd2b1f5/alarm/trigger"
            
            Log.d(TAG, "📡 Subscribing to topics...")
            
            mqttClient?.subscribeWith()
                ?.topicFilter(topicStatus)
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
                ?.callback { message ->
                    try {
                        val payload = String(message.payloadAsBytes)
                        Log.d(TAG, "📥 STATUS: $payload")
                        SystemState.parseStatusFromJson(payload)
                        onConnectionStatusChange?.invoke(true, "Статус обновлён")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing status: ${e.message}", e)
                    }
                }
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Subscribe to status failed: ${throwable.message}")
                    } else {
                        Log.d(TAG, "✅ Subscribed to: $topicStatus")
                    }
                }
            
            mqttClient?.subscribeWith()
                ?.topicFilter(topicTrigger)
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
                ?.callback { message ->
                    try {
                        val payload = String(message.payloadAsBytes)
                        Log.d(TAG, "🚨 ALARM: $payload")
                        SystemState.handleAlarmEvent(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing alarm: ${e.message}", e)
                    }
                }
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Subscribe to trigger failed: ${throwable.message}")
                    } else {
                        Log.d(TAG, "✅ Subscribed to: $topicTrigger")
                    }
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ subscribeToTopics error: ${e.message}", e)
        }
    }
    
    private fun publishCommand(command: String) {
        try {
            val topic = SystemState.mqttSettings.topicControl
            
            mqttClient?.publishWith()
                ?.topic(topic)
                ?.payload(command.toByteArray())
                ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e(TAG, "❌ Publish failed: ${throwable.message}")
                        onConnectionStatusChange?.invoke(false, "Ошибка отправки")
                    } else {
                        Log.d(TAG, "📤 Published: $command to $topic")
                        onConnectionStatusChange?.invoke(true, "Команда отправлена")
                    }
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ publishCommand error: ${e.message}", e)
            onConnectionStatusChange?.invoke(false, "Ошибка: ${e.message}")
        }
    }
    
    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient = null
            isConnecting = false
            onConnectionStatusChange?.invoke(false, "Отключено")
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}", e)
        }
    }
}
