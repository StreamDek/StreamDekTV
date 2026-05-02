package com.streamdek.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.ui.StreamDekTvApp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.AppTheme)
    super.onCreate(savedInstanceState)
    AppGraph.initialize(applicationContext)
    setContent {
      StreamDekTvApp()
    }
  }
}
