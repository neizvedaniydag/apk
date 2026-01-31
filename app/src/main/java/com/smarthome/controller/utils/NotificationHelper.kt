package com.smarthome.controller.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.smarthome.controller.data.HistoryEvent
import com.smarthome.controller.data.HistoryRepository

object NotificationHelper {
    private const val CHANNEL_ID = "alarm_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SmartHome Alarm"
            val descriptionText = "Уведомления о тревоге и движении"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

fun sendNotification(context: Context, title: String, message: String) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
        != PackageManager.PERMISSION_GRANTED) return

    // Существующий код уведомления...
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)

    with(NotificationManagerCompat.from(context)) {
        notify(System.currentTimeMillis().toInt(), builder.build())
    }
    
    // 🔥 ДОБАВЬТЕ ЭТО:
    GlobalScope.launch(Dispatchers.IO) {
        HistoryRepository.addEvent(
            HistoryEvent(
                timestamp = System.currentTimeMillis(),
                type = "ALARM",
                title = title,
                message = message,
                imagePath = null
            )
        )
    }
}


    // === ВОТ ЭТОТ МЕТОД, КОТОРОГО НЕ ХВАТАЛО ===
    fun sendImageNotification(context: Context, title: String, image: Bitmap) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("📷 Получено фото с камеры. Разверните для просмотра.")
            .setLargeIcon(image) // Иконка справа
            .setStyle(NotificationCompat.BigPictureStyle()
                .bigPicture(image)
                .bigLargeIcon(null as Bitmap?)) // Скрываем иконку при разворачивании
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}
