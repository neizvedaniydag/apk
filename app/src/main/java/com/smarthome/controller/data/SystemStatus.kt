package com.smarthome.controller.data

data class SystemStatus(
    val alarmEnabled: Boolean = false,
    val soundLevel: Int = 0,
    val pirStatus: String = "CLEAR",
    val systemLocked: Boolean = false,
    val isAlarm: Boolean = false,
    val lastUpdate: Long = 0
)
