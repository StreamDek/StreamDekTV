package com.streamdek.tv.nativeapp

import android.content.Context
import com.streamdek.tv.nativeapp.data.AuthSessionStore
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.update.AppUpdateManager

object AppGraph {
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val repository: StreamDekRepository by lazy {
        StreamDekRepository(
            sessionStore = AuthSessionStore(appContext),
        )
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(
            context = appContext,
            repository = repository,
        )
    }
}
