package com.smarthome.controller.data

enum class ConnectionMode {
    SMS_ONLY,
    MQTT_ONLY,
    HYBRID // SMS + MQTT (по умолчанию)
}
