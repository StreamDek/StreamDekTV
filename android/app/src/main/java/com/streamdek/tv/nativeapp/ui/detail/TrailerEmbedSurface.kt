package com.streamdek.tv.nativeapp.ui.detail

import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.streamdek.tv.nativeapp.data.TvDebugLogger

/**
 * The trailer as YouTube's own embed, for when the player API will not hand over a file.
 *
 * Carried over from StreamDek Mobile, which has run this fallback for long enough to know it holds:
 * the extraction path depends on which internal client YouTube is currently answering, and that
 * moves. The embed is the published interface and keeps playing when extraction does not — including
 * the "confirm you're not a bot" wall, which is about a proof-of-origin token rather than about who
 * is asking, and which no amount of signing in would answer.
 *
 * It is second rather than first because extraction gives a real video surface: correct aspect
 * handling, the app's own load control, no chrome of any kind. This is what stands behind it.
 *
 * Nothing here is interactive. The web page cannot be reached with the remote — the surface refuses
 * focus and swallows touch, so the stage above keeps the keys and Back still means Back. Playback is
 * driven from Kotlin through the iframe API instead.
 */
@Composable
internal fun TrailerEmbedSurface(
    youtubeKey: String,
    playing: Boolean,
    onEnded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestEnded = rememberUpdatedState(onEnded)
    val latestFailed = rememberUpdatedState(onFailed)
    var webView by remember(youtubeKey) { mutableStateOf<WebView?>(null) }

    // Driven from here rather than from a reload, so pausing does not restart the trailer.
    LaunchedEffect(webView, playing) {
        val view = webView ?: return@LaunchedEffect
        val call = if (playing) "playVideo" else "pauseVideo"
        view.evaluateJavascript(
            "(function(){ if (window.streamdekPlayer && window.streamdekPlayer.$call) window.streamdekPlayer.$call(); })()",
            null,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // The page is scenery, not a control. Left focusable it would take the remote from
                // the stage and the viewer would be pressing keys into a web page with no way out.
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setOnTouchListener { _, _ -> true }
                webChromeClient = WebChromeClient()
                webViewClient = trailerEmbedClient(
                    onEnded = { latestEnded.value() },
                    onFailed = { latestFailed.value() },
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = WebSettings.getDefaultUserAgent(context)
                // Nothing about a trailer needs a gesture first; the viewer already asked for it.
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                // The television stays anonymous here as it does everywhere else in this feature —
                // mobile allows cookies because it once had a sign-in to carry, and this never did.
                android.webkit.CookieManager.getInstance().setAcceptCookie(false)
                loadDataWithBaseURL(
                    "https://www.youtube.com/",
                    trailerEmbedHtml(youtubeKey),
                    "text/html",
                    "UTF-8",
                    null,
                )
                webView = this
            }
        },
        onRelease = { view ->
            webView = null
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}

/**
 * Watches the embed and reports the two outcomes the stage above has to act on.
 *
 * The iframe API does not call back into Kotlin, so the page keeps its own state in a variable and
 * this reads it on a timer. A trailer that is playing is polled slowly to notice the end; one that
 * has not started yet is given fifteen seconds of one-second attempts before it is called a failure,
 * which covers a cold WebView on a streaming stick without leaving a viewer looking at black.
 */
private fun trailerEmbedClient(onEnded: () -> Unit, onFailed: () -> Unit): WebViewClient =
    object : WebViewClient() {
        private fun inspect(view: WebView, attempt: Int) {
            view.evaluateJavascript(
                "(function(){ return window.streamdekPlayback ? window.streamdekPlayback() : 'LOADING'; })()",
            ) { state ->
                when {
                    state.contains("ENDED") -> onEnded()
                    state.contains("ERROR") -> {
                        TvDebugLogger.w("Trailer", "embed reported an error")
                        onFailed()
                    }
                    state.contains("READY") -> view.postDelayed({ inspect(view, 0) }, 1_000)
                    attempt < 15 -> view.postDelayed({ inspect(view, attempt + 1) }, 1_000)
                    else -> {
                        TvDebugLogger.w("Trailer", "embed never started")
                        onFailed()
                    }
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            view.postDelayed({ inspect(view, 0) }, 500)
        }

        override fun onReceivedError(
            view: WebView,
            request: android.webkit.WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                TvDebugLogger.w("Trailer", "embed main-frame error=${error.errorCode}")
                onFailed()
            }
        }
    }

/**
 * The embed page, sized to the television and stripped of everything but the picture.
 *
 * `controls=0` and `disablekb=1` because there is nothing here to operate: the remote belongs to the
 * stage above, which pauses and exits. `rel=0` and `modestbranding=1` keep the end of a trailer from
 * turning into a wall of somebody else's videos.
 *
 * Sound is on. This is the television's treatment — the trailer has the screen, and a silent one
 * across a room is a video with something wrong with it.
 */
internal fun trailerEmbedHtml(youtubeKey: String): String = """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
        <style>
          html, body, #player, iframe {
            position: fixed !important;
            inset: 0 !important;
            width: 100vw !important;
            height: 100vh !important;
            margin: 0 !important;
            padding: 0 !important;
            border: 0 !important;
            overflow: hidden !important;
            background: #000 !important;
            pointer-events: none !important;
          }
        </style>
      </head>
      <body>
        <div id="player"></div>
        <script>
          let streamdekState = 'LOADING';
          function onYouTubeIframeAPIReady() {
            window.streamdekPlayer = new YT.Player('player', {
              videoId: '$youtubeKey',
              playerVars: {
                autoplay: 1,
                controls: 0,
                disablekb: 1,
                fs: 0,
                iv_load_policy: 3,
                rel: 0,
                playsinline: 1,
                modestbranding: 1,
                origin: 'https://www.youtube.com'
              },
              events: {
                onReady: function(event) { event.target.unMute(); event.target.playVideo(); streamdekState = 'READY'; },
                onStateChange: function(event) {
                  if (event.data === YT.PlayerState.ENDED) streamdekState = 'ENDED';
                  else if (event.data === YT.PlayerState.PLAYING || event.data === YT.PlayerState.BUFFERING) streamdekState = 'READY';
                },
                onError: function() { streamdekState = 'ERROR'; }
              }
            });
          }
          window.streamdekPlayback = function() { return streamdekState; };
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
      </body>
    </html>
""".trimIndent()
