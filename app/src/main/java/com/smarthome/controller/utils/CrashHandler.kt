package com.smarthome.controller.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashHandler : Thread.UncaughtExceptionHandler {
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null
    
    fun install(ctx: Context) {
        context = ctx
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashLog = buildString {
                appendLine("=== КРАШ ПРИЛОЖЕНИЯ ===")
                appendLine("Время: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine()
                appendLine("=== ОШИБКА ===")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                appendLine(sw.toString())
                appendLine()
                appendLine("=== УСТРОЙСТВО ===")
                appendLine("Модель: ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
            }
            
            context?.getExternalFilesDir(null)?.let { dir ->
                File(dir, "crash.log").writeText(crashLog)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    fun getCrashLog(context: Context): String? {
        val file = File(context.getExternalFilesDir(null), "crash.log")
        return if (file.exists()) file.readText() else null
    }
    
    fun clearCrashLog(context: Context) {
        File(context.getExternalFilesDir(null), "crash.log").delete()
    }
}
