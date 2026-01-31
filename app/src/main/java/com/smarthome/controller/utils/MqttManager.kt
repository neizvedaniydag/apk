package com.smarthome.controller.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.data.SystemStatus
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object MqttManager {
    private const val TAG = "MqttManager"
    
    private const val SERVER_URI = "tcp://srv2.clusterfly.ru:9991"
    private const val USERNAME = "user_4bd2b1f5"
    private const val PASSWORD = "FnoQuMvkcV1ej"
    
    private const val TOPIC_STATUS = "$USERNAME/status"
    private const val TOPIC_COMMANDS = "$USERNAME/commands"
    private const val TOPIC_STREAM = "$USERNAME/camera/stream"
    private const val TOPIC_SNAPSHOT = "$USERNAME/camera/alarm_snapshot"
    
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR;
        
        val isConnected: Boolean get() = this == CONNECTED
        val statusText: String get() = when(this) {
            DISCONNECTED -> "Не подключено"
            CONNECTING -> "Подключение..."
            CONNECTED -> "Подключено"
            ERROR -> "Ошибка подключения"
        }
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val streamLock = ReentrantLock()
    private var streamBuffer = ByteArrayOutputStream(200000)
    private var isReceivingStream = false
    private var lastStreamChunk = 0L
    
    private val previewLock = ReentrantLock()
    private var previewBuffer = ByteArrayOutputStream(200000)
    private var isReceivingPreview = false
    private var lastPreviewChunk = 0L
    
    private const val CHUNK_TIMEOUT = 3000L
    
    private var mqttClient: MqttAndroidClient? = null
    private var videoStreamCallback: ((ByteArray?, Int?, String?) -> Unit)? = null
    
    private val _latestPreview = MutableStateFlow<Bitmap?>(null)
    val latestPreview: StateFlow<Bitmap?> = _latestPreview
    
    fun startAutoConnect(context: Context) {
        if (_connectionState.value == ConnectionState.CONNECTED || 
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connected/connecting")
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        initialize(context)
    }
    
    private fun initialize(context: Context) {
        if (mqttClient != null) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        val clientId = "SmartHome_${System.currentTimeMillis()}"
        
        mqttClient = MqttAndroidClient(context, SERVER_URI, clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "✅ Connected: $serverURI")
                    _connectionState.value = ConnectionState.CONNECTED
                    subscribeToTopics()
                }
                
                override fun connectionLost(cause: Throwable?) {
                    Log.e(TAG, "❌ Connection lost", cause)
                    _connectionState.value = ConnectionState.DISCONNECTED
                    resetAllBuffers()
                }
                
                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, message.payload)
                }
                
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        }
        
        val options = MqttConnectOptions().apply {
            userName = USERNAME
            password = PASSWORD.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 10
            keepAliveInterval = 20
            maxInflight = 100
        }
        
        try {
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "✅ MQTT connected")
                    _connectionState.value = ConnectionState.CONNECTED
                }
                
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "❌ Connect failed", exception)
                    _connectionState.value = ConnectionState.ERROR
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection error", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }
    
    private fun subscribeToTopics() {
        try {
            mqttClient?.subscribe(
                arrayOf(TOPIC_STATUS, TOPIC_STREAM, TOPIC_SNAPSHOT),
                intArrayOf(1, 0, 1)  // 🔥 QoS 1 для STATUS
            )
            Log.d(TAG, "📡 Subscribed to all topics")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Subscribe error", e)
        }
    }
    
    private fun handleMessage(topic: String, payload: ByteArray) {
        when (topic) {
            TOPIC_STREAM -> handleStreamChunk(payload)
            TOPIC_SNAPSHOT -> handleSnapshot(payload)
            TOPIC_STATUS -> handleStatus(payload)  // 🔥 ИСПРАВЛЕНО
            else -> Log.w(TAG, "⚠️ Unknown topic: $topic")
        }
    }
    
    /**
     * 🔥 НОВАЯ ФУНКЦИЯ: Парсинг и обновление SystemState
     */
    private fun handleStatus(payload: ByteArray) {
        try {
            val json = String(payload, Charsets.UTF_8)
            Log.d(TAG, "📊 Status JSON: $json")
            
            // Парсинг JSON
            val systemLocked = json.contains("\"systemLocked\":true") || 
                              json.contains("\"systemLocked\": true")
            
            val alarmEnabled = json.contains("\"alarmEnabled\":true") || 
                              json.contains("\"alarmEnabled\": true")
            
            val pirStatus = when {
                json.contains("\"pirStatus\":\"ALERT\"") || 
                json.contains("\"pirStatus\": \"ALERT\"") -> "ALERT"
                
                json.contains("\"pirStatus\":\"DETECTED\"") || 
                json.contains("\"pirStatus\": \"DETECTED\"") -> "DETECTED"
                
                json.contains("\"pirStatus\":\"MOTION\"") || 
                json.contains("\"pirStatus\": \"MOTION\"") -> "MOTION"
                
                else -> "NONE"
            }
            
            val soundLevel = Regex("\"soundLevel\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            Log.d(TAG, "✅ Parsed: locked=$systemLocked, alarm=$alarmEnabled, pir=$pirStatus, sound=$soundLevel")
            
            // 🔥 ГЛАВНОЕ: Обновляем SystemState
            val newStatus = SystemStatus(
                systemLocked = systemLocked,
                alarmEnabled = alarmEnabled,
                pirStatus = pirStatus,
                soundLevel = soundLevel,
                lastUpdate = System.currentTimeMillis(),
                isAlarm = pirStatus == "ALERT" || pirStatus == "MOTION"
            )
            
            SystemState.updateStatus(newStatus)
            
            Log.d(TAG, "🔄 SystemState updated successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Status parse error", e)
        }
    }
    
    private fun handleStreamChunk(payload: ByteArray) {
        streamLock.withLock {
            try {
                val now = System.currentTimeMillis()
                
                if (isReceivingStream && (now - lastStreamChunk) > CHUNK_TIMEOUT) {
                    Log.w(TAG, "⏱️ Stream timeout")
                    resetStreamBuffer()
                }
                
                val marker = try {
                    if (payload.size <= 10) payload.toString(Charsets.UTF_8) else null
                } catch (e: Exception) { 
                    null 
                }
                
                when {
                    marker == "START" -> {
                        resetStreamBuffer()
                        isReceivingStream = true
                        lastStreamChunk = now
                        Log.d(TAG, "📥 START stream")
                    }
                    marker == "END" -> {
                        if (isReceivingStream) {
                            val frameData = streamBuffer.toByteArray()
                            Log.d(TAG, "✅ END stream: ${frameData.size} bytes")
                            processFrame(frameData, isPreview = false)
                            resetStreamBuffer()
                        } else {
                            Log.w(TAG, "⚠️ END without START")
                        }
                    }
                    isReceivingStream -> {
                        streamBuffer.write(payload)
                        lastStreamChunk = now
                    }
                    else -> {
                        // Игнорируем
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Stream error", e)
                resetStreamBuffer()
            }
        }
    }
private fun handleSnapshot(payload: ByteArray) {
    previewLock.withLock {
        try {
            val now = System.currentTimeMillis()
            
            if (isReceivingPreview && (now - lastPreviewChunk) > CHUNK_TIMEOUT) {
                Log.w(TAG, "⏱️ Preview timeout")
                resetPreviewBuffer()
            }
            
            val marker = try {
                if (payload.size <= 10) payload.toString(Charsets.UTF_8) else null
            } catch (e: Exception) { 
                null 
            }
            
            when {
                marker == "START" -> {
                    resetPreviewBuffer()
                    isReceivingPreview = true
                    lastPreviewChunk = now
                    Log.d(TAG, "📥 START preview")
                }
                marker == "END" -> {
                    if (isReceivingPreview) {
                        val frameData = previewBuffer.toByteArray()
                        Log.d(TAG, "✅ END preview: ${frameData.size} bytes")
                        processFrame(frameData, isPreview = true)
                        resetPreviewBuffer()
                    } else {
                        Log.d(TAG, "📸 Direct snapshot: ${payload.size} bytes")
                        processFrame(payload, isPreview = true)
                    }
                }
                isReceivingPreview -> {
                    previewBuffer.write(payload)
                    lastPreviewChunk = now
                }
                else -> {
                    // 🔥 ИСПРАВЛЕНО: Добавлен else
                    if (payload.size > 1000) {
                        Log.d(TAG, "📸 Full preview: ${payload.size} bytes")
                        processFrame(payload, isPreview = true)
                    } else {
                        // Маленькие пакеты игнорируем
                        Log.d(TAG, "⚠️ Small payload ignored: ${payload.size} bytes")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Snapshot error", e)
            resetPreviewBuffer()
        }
    }
}

    
    private fun processFrame(frameData: ByteArray, isPreview: Boolean) {
        try {
            if (frameData.size < 4 ||
                frameData[0] != 0xFF.toByte() ||
                frameData[1] != 0xD8.toByte()) {
                Log.e(TAG, "❌ Invalid JPEG signature")
                return
            }
            
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
                inTempStorage = ByteArray(16 * 1024)
            }
            
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
            
            if (bitmap != null) {
                if (isPreview) {
                    _latestPreview.value?.recycle()
                    _latestPreview.value = bitmap
                    Log.d(TAG, "🖼️ Preview: ${bitmap.width}x${bitmap.height}")
                } else {
                    videoStreamCallback?.invoke(frameData, null, null)
                }
            } else {
                Log.e(TAG, "❌ Bitmap decode failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decode error", e)
        }
    }
    
    private fun resetStreamBuffer() {
        streamBuffer.reset()
        isReceivingStream = false
        lastStreamChunk = 0L
    }
    
    private fun resetPreviewBuffer() {
        previewBuffer.reset()
        isReceivingPreview = false
        lastPreviewChunk = 0L
    }
    
    private fun resetAllBuffers() {
        streamLock.withLock { resetStreamBuffer() }
        previewLock.withLock { resetPreviewBuffer() }
    }
    
    fun subscribeToVideoStream(callback: (ByteArray?, Int?, String?) -> Unit) {
        videoStreamCallback = callback
        Log.d(TAG, "📹 Video stream subscribed")
    }
    
    fun unsubscribeFromVideoStream() {
        videoStreamCallback = null
        Log.d(TAG, "📹 Video stream unsubscribed")
    }
    
    fun sendCommand(context: Context, command: String) {
        sendCameraCommand(context, command)
    }
    
    fun sendCameraCommand(context: Context, command: String) {
        if (mqttClient == null) {
            startAutoConnect(context)
        }
        
        try {
            val message = MqttMessage(command.toByteArray()).apply {
                qos = 0
                isRetained = false
            }
            mqttClient?.publish(TOPIC_COMMANDS, message)
            Log.d(TAG, "📤 Command sent: $command")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send command error", e)
        }
    }
    
    fun disconnect() {
        try {
            resetAllBuffers()
            videoStreamCallback = null
            _latestPreview.value?.recycle()
            _latestPreview.value = null
            mqttClient?.disconnect()
            mqttClient = null
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.d(TAG, "🔌 Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Disconnect error", e)
        }
    }
    
    fun stopAutoConnect() {
        try {
            _connectionState.value = ConnectionState.DISCONNECTED
            resetAllBuffers()
            videoStreamCallback = null
            _latestPreview.value?.recycle()
            _latestPreview.value = null
            
            mqttClient?.let { client ->
                try {
                    if (client.isConnected) {
                        client.disconnect(0)
                    }
                    client.unregisterResources()
                    client.close()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Disconnect error", e)
                }
            }
            
            mqttClient = null
            Log.d(TAG, "🛑 Auto-connect stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Stop error", e)
        }
    }
}
