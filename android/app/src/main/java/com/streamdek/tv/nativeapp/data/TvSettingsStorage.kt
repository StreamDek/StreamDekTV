package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.content.SharedPreferences
import com.streamdek.tv.R

internal fun Context.durableTvPreferences(name: String): SharedPreferences =
    DurableTvPreferences(applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)) {
        TvDebugLogger.w("Settings", "disk_write_failed")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, R.string.settings_save_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

internal class DurableTvPreferences(private val storage: SharedPreferences, private val failed: () -> Unit = {}) : SharedPreferences by storage {
    override fun edit(): SharedPreferences.Editor {
        val editor = storage.edit()
        return object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { editor.putStringSet(key, values) }
            override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
            override fun remove(key: String) = apply { editor.remove(key) }
            override fun clear() = apply { editor.clear() }
            override fun apply() { commit() }
            override fun commit(): Boolean = editor.commit().also { if (!it) failed() }
        }
    }
}
