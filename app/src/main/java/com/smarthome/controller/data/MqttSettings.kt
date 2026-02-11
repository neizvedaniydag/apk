package com.smarthome.controller.data

data class MqttSettings(
    val server: String = "srv2.clusterfly.ru",
    val port: Int = 9991,
    val username: String = "user_4bd2b1f5",
    val password: String = "FnoQuMvkcV1ej",
    val clientId: String = "user_4bd2b1f5_android",
    val topicControl: String = "user_4bd2b1f5/alarm/control",
    val topicStatus: String = "user_4bd2b1f5/alarm/status",
    val topicLog: String = "user_4bd2b1f5/alarm/log"
)
