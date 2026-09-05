package com.streamdek.tv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.data.Perf
import com.streamdek.tv.nativeapp.ui.StreamDekTvApp
import com.streamdek.tv.nativeapp.ui.localizedAppContext

internal object TvRemoteKeyRouter {
  @Volatile var onKeyUp: ((Int) -> Boolean)? = null
}

class MainActivity : ComponentActivity() {
  /**
   * So the window this activity opens with is already in the selected interface language.
   *
   * The composition does not rely on this - ProvideAppLocale overrides the locals `stringResource`
   * reads, and is what lets a language change take effect without rebuilding the activity, which on
   * a television would reconstruct every focus requester in the tree and drop the remote's focus.
   * This covers the frame before the first composition, and anything outside it that resolves a
   * resource against the activity rather than against the composition.
   */
  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(localizedAppContext(newBase))
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP && TvRemoteKeyRouter.onKeyUp?.invoke(event.keyCode) == true) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.AppTheme)
    super.onCreate(savedInstanceState)
    Perf.startupMark("activity.onCreate")
    AppGraph.initialize(applicationContext)
    setContent {
      Perf.startupMark("activity.firstComposition")
      StreamDekTvApp()
    }
  }
}
