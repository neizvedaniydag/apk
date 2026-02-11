package com.smarthome.controller.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SystemState {
    private const val PREFS_NAME = "alarm_settings"
    private const val KEY_PHONE = "phone_number"
    private const val KEY_ALARM_ENABLED = "alarm_enabled"
    private const val KEY_CONNECTION_MODE = "connection_mode"
    private const val KEY_AUTO_UPDATE = "auto_update_enabled"
    private const val KEY_MQTT_SERVER = "mqtt_server"
    private const val KEY_MQTT_PORT = "mqtt_port"
    private const val KEY_MQTT_USER = "mqtt_user"
    private const val KEY_MQTT_PASS = "mqtt_pass"
    private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
    
    private lateinit var prefs: SharedPreferences
    
    private val _currentStatusFlow = MutableStateFlow(SystemStatus())
    val currentStatusFlow: StateFlow<SystemStatus> = _currentStatusFlow.asStateFlow()
    
    val currentStatus: SystemStatus
        get() = _currentStatusFlow.value
    
    var phoneNumber: String = ""
        private set
    
    var connectionMode: ConnectionMode = ConnectionMode.HYBRID
        private set
    
    var mqttSettings: MqttSettings = MqttSettings()
        private set
    
    var autoUpdateEnabled: Boolean = true
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        phoneNumber = prefs.getString(KEY_PHONE, "+79964228371") ?: "+79964228371"
        autoUpdateEnabled = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        val modeStr = prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.HYBRID.name) ?: ConnectionMode.HYBRID.name
        connectionMode = try {
            ConnectionMode.valueOf(modeStr)
        } catch (e: Exception) {
            ConnectionMode.HYBRID
        }
        mqttSettings = MqttSettings(
            server = prefs.getString(KEY_MQTT_SERVER, "srv2.clusterfly.ru") ?: "srv2.clusterfly.ru",
            port = prefs.getInt(KEY_MQTT_PORT, 9991),
            username = prefs.getString(KEY_MQTT_USER, "user_4bd2b1f5") ?: "user_4bd2b1f5",
            password = prefs.getString(KEY_MQTT_PASS, "FnoQuMvkcV1ej") ?: "FnoQuMvkcV1ej",
            clientId = prefs.getString(KEY_MQTT_CLIENT_ID, "user_4bd2b1f5_android") ?: "user_4bd2b1f5_android"
        )
    }

    fun savePhoneNumber(phone: String) {
        phoneNumber = phone
        prefs.edit().putString(KEY_PHONE, phone).apply()
    }

    fun saveConnectionMode(mode: ConnectionMode) {
        connectionMode = mode
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }
    
    fun saveMqttSettings(settings: MqttSettings) {
        mqttSettings = settings
        prefs.edit().apply {
            putString(KEY_MQTT_SERVER, settings.server)
            putInt(KEY_MQTT_PORT, settings.port)
            putString(KEY_MQTT_USER, settings.username)
            putString(KEY_MQTT_PASS, settings.password)
            putString(KEY_MQTT_CLIENT_ID, settings.clientId)
            apply()
        }
    }
    
    fun saveGeneralSettings(phone: String, autoUpdate: Boolean) {
        phoneNumber = phone
        autoUpdateEnabled = autoUpdate
        prefs.edit().apply {
            putString(KEY_PHONE, phone)
            putBoolean(KEY_AUTO_UPDATE, autoUpdate)
            apply()
        }
    }
    
    fun saveSettings() {
        prefs.edit().apply {
            putString(KEY_PHONE, phoneNumber)
            putBoolean(KEY_AUTO_UPDATE, autoUpdateEnabled)
            putString(KEY_CONNECTION_MODE, connectionMode.name)
            putString(KEY_MQTT_SERVER, mqttSettings.server)
            putInt(KEY_MQTT_PORT, mqttSettings.port)
            putString(KEY_MQTT_USER, mqttSettings.username)
            putString(KEY_MQTT_PASS, mqttSettings.password)
            putString(KEY_MQTT_CLIENT_ID, mqttSettings.clientId)
            apply()
        }
    }

    fun updateStatus(status: SystemStatus) {
        _currentStatusFlow.value = status
    }

    fun clearAlarm() {
        val current = _currentStatusFlow.value
        _currentStatusFlow.value = current.copy(isAlarm = false)
    }
}