package com.stickermaker.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

class StickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/**
 * Keeps a short report of the last crash so it can be shown (and copied) on the next launch.
 * Covers Kotlin/Java crashes, and on Android 11+ also native crashes, freezes and out-of-memory kills.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash"
    private const val KEY_SEEN = "seen_exit_ts"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE).writeText(header(app) + error.stackTraceToString().take(8000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the report of the last crash once, then forgets it. */
    fun takeReport(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(ctx.filesDir, FILE)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            file.delete()
            prefs.edit().putLong(KEY_SEEN, System.currentTimeMillis()).apply()
            if (!text.isNullOrBlank()) return text
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() }
            .getOrNull() ?: return null
        val seen = prefs.getLong(KEY_SEEN, 0)
        if (info.timestamp <= seen) return null
        prefs.edit().putLong(KEY_SEEN, info.timestamp).apply()

        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR (freeze)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            else -> return null   // normal exits: user closed it, system cleanup, etc.
        }
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull()
        } else null
        return buildString {
            append(header(ctx))
            append("Exit: ").append(reason).append('\n')
            append("Description: ").append(info.description ?: "-").append('\n')
            append("Memory (PSS KB): ").append(info.pss).append('\n')
            if (trace != null) append('\n').append(trace)
        }
    }

    private fun header(ctx: Context): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return "App $version · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n"
    }
}
