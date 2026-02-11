package com.smarthome.controller.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.smarthome.controller.data.HistoryEvent
import com.smarthome.controller.data.HistoryRepository
import com.smarthome.controller.data.SystemState
import com.smarthome.controller.data.SystemStatus
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object MqttManager {
    private const val TAG = "MqttManager"

    private const val SERVER_URI = "tcp://srv2.clusterfly.ru:9991"
    private const val USERNAME = "user_4bd2b1f5"
    private const val PASSWORD = "FnoQuMvkcV1ej"

    // Топики для получения данных
    private const val TOPIC_STATUS = "$USERNAME/status"
    private const val TOPIC_ALARM_STATUS = "$USERNAME/alarm/status"
    private const val TOPIC_ALARM_SOUND = "$USERNAME/alarm/sound"
    private const val TOPIC_ALARM_MOTION = "$USERNAME/alarm/motion"
    private const val TOPIC_STREAM = "$USERNAME/camera/stream"
    private const val TOPIC_SNAPSHOT = "$USERNAME/camera/alarm_snapshot"

    // Топики для отправки команд
    private const val TOPIC_CAMERA_COMMANDS = "$USERNAME/camera/control"
    private const val TOPIC_ALARM_COMMANDS = "$USERNAME/alarm/control"

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

    // Время последнего получения реальных данных от ESP (статус, кадр, звук)
    private val _lastDataReceived = MutableStateFlow(0L)
    val lastDataReceived: StateFlow<Long> = _lastDataReceived

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
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Время последнего запроса превью — чтобы отличить превью от реальных детекций
    private var lastPreviewRequestTime = 0L
    private const val PREVIEW_RESPONSE_WINDOW = 8000L // 8 сек на ответ

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

        appContext = context.applicationContext
        val clientId = "SmartHome_${System.currentTimeMillis()}"

        mqttClient = MqttAndroidClient(context, SERVER_URI, clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "Connected: $serverURI")
                    _connectionState.value = ConnectionState.CONNECTED
                    subscribeToTopics()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.e(TAG, "Connection lost", cause)
                    _connectionState.value = ConnectionState.DISCONNECTED
                    resetAllBuffers()
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, message.payload, message.isRetained)
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
                    Log.d(TAG, "MQTT connected")
                    _connectionState.value = ConnectionState.CONNECTED
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Connect failed", exception)
                    _connectionState.value = ConnectionState.ERROR
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun subscribeToTopics() {
        try {
            mqttClient?.subscribe(
                arrayOf(
                    TOPIC_STATUS,
                    TOPIC_ALARM_STATUS,
                    TOPIC_ALARM_SOUND,
                    TOPIC_ALARM_MOTION,
                    TOPIC_STREAM,
                    TOPIC_SNAPSHOT
                ),
                intArrayOf(1, 1, 0, 0, 0, 1)
            )
            Log.d(TAG, "Subscribed to all topics")
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe error", e)
        }
    }

    private fun handleMessage(topic: String, payload: ByteArray, retained: Boolean) {
        when (topic) {
            TOPIC_STREAM -> handleStreamChunk(payload)
            TOPIC_SNAPSHOT -> handleSnapshot(payload)
            TOPIC_STATUS -> handleStatus(payload, retained)
            TOPIC_ALARM_STATUS -> handleAlarmStatus(payload, retained)
            TOPIC_ALARM_SOUND -> handleAlarmSound(payload, retained)
            TOPIC_ALARM_MOTION -> handleAlarmMotion(payload, retained)
            else -> Log.w(TAG, "Unknown topic: $topic")
        }
    }

    /**
     * Обработка статуса от камеры (topic: user/status)
     * Камера шлёт JSON: {"streaming":true,"fps":10,"wifi_rssi":-50}
     */
    private fun handleStatus(payload: ByteArray, retained: Boolean) {
        try {
            val json = String(payload, Charsets.UTF_8)
            Log.d(TAG, "Camera status JSON (retained=$retained): $json")

            if (retained) {
                // Retained-сообщение — не обновляем lastDataReceived,
                // не ставим тревогу, только базовые поля
                Log.d(TAG, "Skipping retained status — not live data")
                return
            }

            _lastDataReceived.value = System.currentTimeMillis()
            parseAndUpdateSystemStatus(json)
        } catch (e: Exception) {
            Log.e(TAG, "Status parse error", e)
        }
    }

    /**
     * Обработка статуса от основной ESP32 (topic: user/alarm/status)
     * ESP32 шлёт JSON: {"armed":true,"locked":false,"pir":true,"wifi_rssi":-45,"uptime":123}
     */
    private fun handleAlarmStatus(payload: ByteArray, retained: Boolean) {
        try {
            val json = String(payload, Charsets.UTF_8)
            Log.d(TAG, "Alarm status JSON (retained=$retained): $json")

            if (retained) {
                Log.d(TAG, "Skipping retained alarm status — not live data")
                return
            }

            _lastDataReceived.value = System.currentTimeMillis()
            parseAndUpdateSystemStatus(json)
        } catch (e: Exception) {
            Log.e(TAG, "Alarm status parse error", e)
        }
    }

    /**
     * Универсальный парсер статуса — понимает оба формата JSON от ESP.
     * Сохраняет текущие значения, если поле отсутствует в JSON.
     */
    private fun parseAndUpdateSystemStatus(json: String) {
        val current = SystemState.currentStatusFlow.value

        // Определяем, есть ли поле armed/alarmEnabled в JSON
        val hasArmedField = json.contains("\"armed\"") || json.contains("\"alarmEnabled\"")
        val hasLockedField = json.contains("\"locked\"") || json.contains("\"systemLocked\"")
        val hasPirField = json.contains("\"pir\"") || json.contains("\"pirStatus\"")
        val hasSoundField = json.contains("\"soundLevel\"") || json.contains("\"sound_level\"")

        val alarmEnabled = if (hasArmedField) {
            json.contains("\"armed\":true") ||
                    json.contains("\"armed\": true") ||
                    json.contains("\"alarmEnabled\":true") ||
                    json.contains("\"alarmEnabled\": true")
        } else current.alarmEnabled

        val systemLocked = if (hasLockedField) {
            json.contains("\"locked\":true") ||
                    json.contains("\"locked\": true") ||
                    json.contains("\"systemLocked\":true") ||
                    json.contains("\"systemLocked\": true")
        } else current.systemLocked

        val pirStatus = if (hasPirField) {
            val pirActive = json.contains("\"pir\":true") ||
                    json.contains("\"pir\": true")
            when {
                json.contains("\"pirStatus\":\"ALERT\"") ||
                        json.contains("\"pirStatus\": \"ALERT\"") -> "ALERT"
                json.contains("\"pirStatus\":\"DETECTED\"") ||
                        json.contains("\"pirStatus\": \"DETECTED\"") -> "DETECTED"
                json.contains("\"pirStatus\":\"MOTION\"") ||
                        json.contains("\"pirStatus\": \"MOTION\"") -> "MOTION"
                pirActive -> "DETECTED"
                else -> "NONE"
            }
        } else current.pirStatus

        val soundLevel = if (hasSoundField) {
            Regex("\"soundLevel\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\"sound_level\"\\s*:\\s*([\\d.]+)").find(json)
                    ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
                ?: current.soundLevel
        } else current.soundLevel

        Log.d(TAG, "Parsed: armed=$alarmEnabled, locked=$systemLocked, pir=$pirStatus, sound=$soundLevel")

        val newStatus = current.copy(
            systemLocked = systemLocked,
            alarmEnabled = alarmEnabled,
            pirStatus = pirStatus,
            soundLevel = soundLevel,
            lastUpdate = System.currentTimeMillis(),
            isAlarm = if (hasPirField) pirStatus == "ALERT" else current.isAlarm
        )

        SystemState.updateStatus(newStatus)
    }

    /**
     * Обработка уровня звука от основной ESP32 (topic: user/alarm/sound)
     * ESP32 шлёт сырое значение ADC: "690.5" (диапазон ~0-4095)
     * Конвертируем в примерные dB: 30-120 dB
     */
    private fun handleAlarmSound(payload: ByteArray, retained: Boolean) {
        try {
            if (retained) return
            val value = String(payload, Charsets.UTF_8).trim()
            val rawValue = value.toFloatOrNull() ?: return
            // Нормализация: ADC 0-4095 -> dB 30-120
            val soundLevel = ((rawValue / 4095f) * 90f + 30f).toInt().coerceIn(0, 130)
            Log.d(TAG, "Sound raw=$rawValue -> ${soundLevel} dB")
            _lastDataReceived.value = System.currentTimeMillis()

            val current = SystemState.currentStatusFlow.value
            SystemState.updateStatus(
                current.copy(
                    soundLevel = soundLevel,
                    lastUpdate = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sound parse error", e)
        }
    }

    /**
     * Обработка движения от основной ESP32 (topic: user/alarm/motion)
     * ESP32 шлёт: "1", "0", "ALARM"
     */
    private fun handleAlarmMotion(payload: ByteArray, retained: Boolean) {
        try {
            if (retained) return
            val value = String(payload, Charsets.UTF_8).trim()
            Log.d(TAG, "Motion: $value")
            _lastDataReceived.value = System.currentTimeMillis()

            val current = SystemState.currentStatusFlow.value
            val pirStatus = when (value.uppercase()) {
                "1" -> "MOTION"
                "ALARM" -> "ALERT"
                else -> "NONE"
            }
            SystemState.updateStatus(
                current.copy(
                    pirStatus = pirStatus,
                    isAlarm = pirStatus == "ALERT",
                    lastUpdate = System.currentTimeMillis()
                )
            )

            // При ALARM сохраняем событие в историю (фото придёт отдельно через snapshot)
            if (pirStatus == "ALERT") {
                scope.launch {
                    try {
                        HistoryRepository.addEvent(
                            HistoryEvent(
                                timestamp = System.currentTimeMillis(),
                                type = "ALARM",
                                title = "Сработала сигнализация",
                                message = "Совпадение: движение + звук",
                                imagePath = null
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save alarm event", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Motion parse error", e)
        }
    }

    /**
     * Обработка видеопотока — поддерживает оба формата:
     * 1) С маркерами START/END (если камера их шлёт)
     * 2) Без маркеров — каждое MQTT-сообщение = один JPEG-кадр
     */
    private fun handleStreamChunk(payload: ByteArray) {
        streamLock.withLock {
            try {
                val now = System.currentTimeMillis()

                if (isReceivingStream && (now - lastStreamChunk) > CHUNK_TIMEOUT) {
                    Log.w(TAG, "Stream timeout")
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
                    }
                    marker == "END" -> {
                        if (isReceivingStream) {
                            val frameData = streamBuffer.toByteArray()
                            Log.d(TAG, "Stream frame: ${frameData.size} bytes")
                            _lastDataReceived.value = now
                            processFrame(frameData, isPreview = false)
                            resetStreamBuffer()
                        }
                    }
                    isReceivingStream -> {
                        streamBuffer.write(payload)
                        lastStreamChunk = now
                    }
                    else -> {
                        // Без маркеров: если это валидный JPEG — обрабатываем как целый кадр
                        if (payload.size > 100 &&
                            payload[0] == 0xFF.toByte() &&
                            payload[1] == 0xD8.toByte()) {
                            Log.d(TAG, "Raw stream frame: ${payload.size} bytes")
                            _lastDataReceived.value = now
                            processFrame(payload, isPreview = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream error", e)
                resetStreamBuffer()
            }
        }
    }

    private fun handleSnapshot(payload: ByteArray) {
        previewLock.withLock {
            try {
                val now = System.currentTimeMillis()

                if (isReceivingPreview && (now - lastPreviewChunk) > CHUNK_TIMEOUT) {
                    Log.w(TAG, "Preview timeout")
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
                    }
                    marker == "END" -> {
                        if (isReceivingPreview) {
                            val frameData = previewBuffer.toByteArray()
                            Log.d(TAG, "Preview frame: ${frameData.size} bytes")
                            _lastDataReceived.value = now
                            processFrame(frameData, isPreview = true)
                            resetPreviewBuffer()
                        } else {
                            // END без START — пробуем как прямой снимок
                            if (payload.size > 1000) {
                                _lastDataReceived.value = now
                                processFrame(payload, isPreview = true)
                            }
                        }
                    }
                    isReceivingPreview -> {
                        previewBuffer.write(payload)
                        lastPreviewChunk = now
                    }
                    else -> {
                        // Без маркеров: если это валидный JPEG — обрабатываем
                        if (payload.size > 1000 &&
                            payload[0] == 0xFF.toByte() &&
                            payload[1] == 0xD8.toByte()) {
                            Log.d(TAG, "Raw snapshot: ${payload.size} bytes")
                            _lastDataReceived.value = now
                            processFrame(payload, isPreview = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot error", e)
                resetPreviewBuffer()
            }
        }
    }

    private fun processFrame(frameData: ByteArray, isPreview: Boolean) {
        try {
            if (frameData.size < 4 ||
                frameData[0] != 0xFF.toByte() ||
                frameData[1] != 0xD8.toByte()) {
                Log.e(TAG, "Invalid JPEG signature")
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
                    Log.d(TAG, "Preview: ${bitmap.width}x${bitmap.height}")

                    // Если это НЕ ответ на наш запрос превью — значит это детекция с камеры
                    val now = System.currentTimeMillis()
                    val isPreviewResponse = (now - lastPreviewRequestTime) < PREVIEW_RESPONSE_WINDOW
                    if (!isPreviewResponse) {
                        saveDetectionToHistory(frameData, now)
                    }
                } else {
                    videoStreamCallback?.invoke(frameData, null, null)
                }
            } else {
                Log.e(TAG, "Bitmap decode failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
        }
    }

    /**
     * Сохраняет снимок детекции на диск и в Room-базу истории.
     * Работает даже если телефон был оффлайн — данные сохраняются локально.
     */
    private fun saveDetectionToHistory(jpegData: ByteArray, timestamp: Long) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                // Сохраняем JPEG на диск
                val dir = File(ctx.filesDir, "detections")
                if (!dir.exists()) dir.mkdirs()

                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val fileName = "det_${dateFormat.format(Date(timestamp))}.jpg"
                val file = File(dir, fileName)

                FileOutputStream(file).use { fos ->
                    fos.write(jpegData)
                }

                Log.d(TAG, "Detection saved: ${file.absolutePath} (${jpegData.size} bytes)")

                // Сохраняем событие в Room
                HistoryRepository.addEvent(
                    HistoryEvent(
                        timestamp = timestamp,
                        type = "ALARM",
                        title = "Обнаружено движение",
                        message = "Камера зафиксировала подозрительную активность",
                        imagePath = file.absolutePath
                    )
                )

                Log.d(TAG, "Detection event added to history")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save detection", e)
            }
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
        Log.d(TAG, "Video stream subscribed")
    }

    fun unsubscribeFromVideoStream() {
        videoStreamCallback = null
        Log.d(TAG, "Video stream unsubscribed")
    }

    /**
     * Отправка команды — автоматически роутит в нужный топик:
     * - Камерные команды (STREAM_ON/OFF/PREVIEW, STATUS) -> camera/control
     * - Команды охраны (ARM, DISARM) -> alarm/control
     */
    fun sendCommand(context: Context, command: String) {
        if (mqttClient == null) {
            startAutoConnect(context)
        }

        val upperCmd = command.trim().uppercase()
        val topic = when {
            upperCmd.startsWith("STREAM") -> TOPIC_CAMERA_COMMANDS
            upperCmd == "ARM" || upperCmd == "DISARM" || upperCmd == "ON" || upperCmd == "OFF" -> TOPIC_ALARM_COMMANDS
            upperCmd == "STATUS" -> {
                // STATUS отправляем в оба топика
                publishToTopic(TOPIC_CAMERA_COMMANDS, command)
                TOPIC_ALARM_COMMANDS
            }
            else -> TOPIC_ALARM_COMMANDS
        }

        publishToTopic(topic, command)
        Log.d(TAG, "Command sent: $command -> $topic")
    }

    fun sendCameraCommand(context: Context, command: String) {
        if (mqttClient == null) {
            startAutoConnect(context)
        }
        // Отмечаем время запроса превью, чтобы отличить ответ от реальной детекции
        if (command.trim().uppercase() == "STREAM_PREVIEW") {
            lastPreviewRequestTime = System.currentTimeMillis()
        }
        publishToTopic(TOPIC_CAMERA_COMMANDS, command)
        Log.d(TAG, "Camera command sent: $command")
    }

    private fun publishToTopic(topic: String, payload: String) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                qos = 0
                isRetained = false
            }
            mqttClient?.publish(topic, message)
        } catch (e: Exception) {
            Log.e(TAG, "Publish error to $topic", e)
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
            _lastDataReceived.value = 0L
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    fun stopAutoConnect() {
        try {
            _connectionState.value = ConnectionState.DISCONNECTED
            _lastDataReceived.value = 0L
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
                    Log.e(TAG, "Disconnect error", e)
                }
            }

            mqttClient = null
            Log.d(TAG, "Auto-connect stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }
}
