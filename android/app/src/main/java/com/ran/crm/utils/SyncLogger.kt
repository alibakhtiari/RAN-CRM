package com.ran.crm.utils

import android.content.Context
import android.util.Log
import com.ran.crm.CrmApplication
import com.ran.crm.data.local.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A centralized logger for sync operations. Logs to Logcat and appends to a local log file for
 * debugging.
 */
object SyncLogger {
    private const val TAG = "RanCrmSync"
    private const val LOG_FILE_NAME = "sync_logs.txt"

    private val dateFormat: SimpleDateFormat
        get() = threadLocalDateFormat.get()!!

    private val threadLocalDateFormat =
            ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    private val scope =
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun log(message: String, error: Throwable? = null) {
        // 1. Log to Logcat
        if (error != null) {
            Log.e(TAG, message, error)
        } else {
            Log.d(TAG, message)
        }

        // 2. Log to File (Async)
        try {
            val context = try { CrmApplication.instance } catch (e: Exception) { null }
            if (context != null) {
                val prefs = PreferenceManager(context)
                if (prefs.isDebugMode) {
                    scope.launch { appendLogToFile(context, message, error) }
                }
            } else {
                Log.w(TAG, "SyncLogger: Cannot log to file - Application instance not ready")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate log write", e)
        }
    }

    private fun appendLogToFile(context: Context, message: String, error: Throwable?) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val timestamp = dateFormat.format(Date())
            val logEntry = buildString {
                append("[$timestamp] $message")
                if (error != null) {
                    append("\nSTACKTRACE: ${Log.getStackTraceString(error)}")
                }
                append("\n")
            }
            file.appendText(logEntry)

            // Rotate logs if too large (e.g., > 5MB)
            if (file.length() > 5 * 1024 * 1024) {
                file.writeText("") // Clear for now, or rename to .old
                file.appendText("[$timestamp] Log rotated\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SyncLogger: ERROR writing to file: ${e.message}", e)
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "No logs found."
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.writeText("")
            log("Logs cleared manually from UI")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
