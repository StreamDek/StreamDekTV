package com.streamdek.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.ui.StreamDekTvApp

internal object TvRemoteKeyRouter {
  @Volatile var onKeyUp: ((Int) -> Boolean)? = null
}

class MainActivity : ComponentActivity() {
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP && TvRemoteKeyRouter.onKeyUp?.invoke(event.keyCode) == true) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.AppTheme)
    super.onCreate(savedInstanceState)
    AppGraph.initialize(applicationContext)
    setContent {
      StreamDekTvApp()
    }
  }
}
