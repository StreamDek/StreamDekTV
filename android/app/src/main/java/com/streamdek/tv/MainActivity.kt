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
    try {
      if (event.action == KeyEvent.ACTION_UP && TvRemoteKeyRouter.onKeyUp?.invoke(event.keyCode) == true) {
        return true
      }
      return super.dispatchKeyEvent(event)
    } catch (error: Throwable) {
      // Navigation categories only: never record typed characters or text entry key codes.
      val key = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select"
        KeyEvent.KEYCODE_BACK -> "back"
        KeyEvent.KEYCODE_MENU -> "menu"
        else -> "other"
      }
      runCatching {
        com.streamdek.tv.nativeapp.data.CrashInputDiagnostics.record(error, key, event.action, event.repeatCount)
      }
      throw error
    }
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
    // The shell paints its own opaque background over the whole screen, so the window's black one
    // beneath it is a full-screen pass per frame that no one sees - on a streaming stick's GPU, a
    // real share of every frame. Dropped once the first frame is up, so start-up still opens on black.
    window.decorView.post { window.setBackgroundDrawable(null) }
  }
}
