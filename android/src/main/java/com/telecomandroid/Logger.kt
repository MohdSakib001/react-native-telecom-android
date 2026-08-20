package com.telecomandroid

import android.util.Log

/**
 * Deliberately not a logging framework. Half of this module runs in a process
 * with no React instance, at 3am, on a device nobody can attach a debugger to;
 * the only thing that matters is that `adb logcat -s RNTelecomAndroid` tells
 * the whole story.
 */
internal object Logger {
    const val TAG = "RNTelecomAndroid"

    const val SILENT = 0
    const val WARN = 1
    const val DEBUG = 2

    @Volatile
    var level: Int = WARN

    fun d(message: String) {
        if (level >= DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (level >= WARN) {
            if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        }
    }

    fun e(message: String, error: Throwable? = null) {
        if (level >= WARN) {
            if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
        }
    }
}
