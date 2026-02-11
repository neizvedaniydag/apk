package com.smarthome.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smarthome.controller.MainActivity

class SmsMonitorService : Service() {
    
    companion object {
        // Каналы
        private const val CHANNEL_SERVICE_ID = "SmartHomeServiceChannel" // Тихий (фоновый)
        private const val CHANNEL_ALARM_ID = "SmartHomeAlarmChannel"     // ГРОМКИЙ (тревога)
        
        // ID уведомлений
        private const val SERVICE_NOTIFICATION_ID = 1337
        private const val ALARM_NOTIFICATION_ID = 2 // Фиксированный ID для тревоги
        
        // ✅ ЭТОЙ ФУНКЦИИ У ТЕБЯ НЕ ХВАТАЛО
        fun clearAlarmNotification(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
            notificationManager.cancel(ALARM_NOTIFICATION_ID)
        }
        
        fun showAlarmNotification(context: Context, message: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
            
            // 1. Создаем канал ТРЕВОГИ (если нет)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val alarmChannel = NotificationChannel(
                    CHANNEL_ALARM_ID,
                    "⚠️ ТРЕВОГА И ОХРАНА",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Критические уведомления о вторжении"
                    enableVibration(true)
                    enableLights(true)
                    setBypassDnd(true) // Пробивать "Не беспокоить"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build()
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
                }
                notificationManager.createNotificationChannel(alarmChannel)
            }
            
            // 2. Подготовка интента
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 3. Формирование текста
            val cleanMessage = when {
                message.contains("ALARM", ignoreCase = true) -> "ОБНАРУЖЕНО ВТОРЖЕНИЕ!"
                message.contains("Motion", ignoreCase = true) -> "Движение на объекте!"
                else -> message
            }
            
            // 4. Сборка уведомления
            val notification = NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
                .setContentTitle("ТРЕВОГА")
                .setContentText(cleanMessage)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setOngoing(true) // Не исчезает само — нужно закрыть вручную или через код
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true) // Heads-up на весь экран
                .setDefaults(Notification.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000)) // Длинная вибрация
                .build()
            
            // Показываем уведомление (перезаписываем старое с тем же ID 2, чтобы не копились)
            notificationManager.notify(ALARM_NOTIFICATION_ID, notification)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createServiceChannel()
        startForeground(SERVICE_NOTIFICATION_ID, createForegroundNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Фоновая работа",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает статус подключения"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }
    
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Мой Дом")
            .setContentText("Мониторинг SMS активен")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
