package com.streamdek.tv.nativeapp.data

import android.util.Log

object TvDebugLogger {
    private const val PREFIX = "StreamDekTV"

    fun d(tag: String, message: String) {
        Log.d("$PREFIX:$tag", message)
    }

    fun i(tag: String, message: String) {
        Log.i("$PREFIX:$tag", message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.w("$PREFIX:$tag", message, error)
        } else {
            Log.w("$PREFIX:$tag", message)
        }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.e("$PREFIX:$tag", message, error)
        } else {
            Log.e("$PREFIX:$tag", message)
        }
    }
}
