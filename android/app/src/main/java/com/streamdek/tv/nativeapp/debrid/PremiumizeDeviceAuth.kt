package com.streamdek.tv.nativeapp.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val TOKEN_ENDPOINT = "https://www.premiumize.me/token"

/**
 * Signing in to Premiumize by showing a short code instead of typing a key.
 *
 * Premiumize's OAuth device flow, which exists for exactly the case StreamDek has: the viewer
 * reads a code off the screen, enters it on a phone or a laptop, and the device is handed a token
 * — no API key typed on a remote control, one character at a time.
 *
 * Only the client id is involved. A phone or a television is a public OAuth client, so a secret
 * compiled into it can be read straight back out of the package and is not a secret; the flow is
 * designed on that basis and never asks for one.
 *
 * The token this produces is used exactly like a typed API key. Premiumize accepts both through
 * the same `Authorization: Bearer` header, so nothing downstream needs to know which kind it
 * holds — see [PremiumizeClient].
 */
object PremiumizeDeviceAuth {

  /** What to put on screen while the viewer authorises the device. */
  data class Started(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    /** How long to leave between polls, as the server asked. */
    val intervalSeconds: Int,
    /** How long the code is good for; after this the viewer has to start again. */
    val expiresInSeconds: Int,
  )

  sealed interface Poll {
    /** Authorised. [accessToken] is stored and used as the account's credential. */
    data class Authorized(val accessToken: String) : Poll

    /** Nobody has entered the code yet. Keep waiting. */
    data object Pending : Poll

    /** Polling too fast. Premiumize asks for more space between attempts. */
    data object SlowDown : Poll

    /** Declined, expired, or refused outright — [message] is fit to show. */
    data class Failed(val message: String) : Poll
  }

  /** Whether this build can offer the flow at all. Blank id means the option stays hidden. */
  fun isConfigured(clientId: String): Boolean = clientId.isNotBlank()

  /**
   * Asks Premiumize for a code pair.
   * POST /token  response_type=device_code&client_id=…
   */
  suspend fun start(clientId: String): Started {
    val body = postForm("response_type" to "device_code", "client_id" to clientId)
    val json = DebridHttp.parseJsonObject(body.payload)
    val deviceCode = json.optString("device_code")
    val userCode = json.optString("user_code")
    if (deviceCode.isBlank() || userCode.isBlank()) {
      throw IllegalStateException(
        errorMessageFor(json) ?: "Premiumize did not return a sign-in code. Try again in a moment.",
      )
    }
    return Started(
      deviceCode = deviceCode,
      userCode = userCode,
      verificationUri = json.optString("verification_uri").ifBlank { "https://www.premiumize.me/device" },
      // Defaults matter: a missing interval polled at full speed is what earns a slow_down.
      intervalSeconds = json.optInt("interval", 5).coerceIn(1, 60),
      expiresInSeconds = json.optInt("expires_in", 600).coerceIn(30, 3600),
    )
  }

  /**
   * Asks whether the code has been entered yet.
   * POST /token  grant_type=device_code&code=…&client_id=…
   *
   * While waiting, Premiumize answers HTTP 400 with an error of `authorization_pending` or
   * `slow_down`. Those are the ordinary path rather than failures, so the status alone cannot be
   * trusted here — the body decides.
   */
  suspend fun poll(clientId: String, deviceCode: String): Poll {
    val body = runCatching {
      postForm("grant_type" to "device_code", "code" to deviceCode, "client_id" to clientId)
    }.getOrElse { error ->
      // A dropped connection mid-wait is not an answer; keep waiting rather than throwing away a
      // code the viewer may already have entered.
      return Poll.Pending
    }

    val json = DebridHttp.parseJsonObject(body.payload)
    val accessToken = json.optString("access_token")
    if (accessToken.isNotBlank()) return Poll.Authorized(accessToken)

    return when (val error = json.optString("error").lowercase()) {
      "authorization_pending" -> Poll.Pending
      "slow_down" -> Poll.SlowDown
      "access_denied" -> Poll.Failed("Sign-in was declined on Premiumize.")
      "expired_token", "invalid_grant" -> Poll.Failed("That code expired. Start again to get a new one.")
      else -> {
        // An empty body on a 200 means nothing has happened yet either.
        if (error.isBlank() && body.successful) Poll.Pending
        else Poll.Failed(errorMessageFor(json) ?: "Premiumize refused the sign-in.")
      }
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────

  private data class Response(val payload: String, val successful: Boolean)

  /** Posts a form and hands back the body whatever the status: 400 carries the real answer here. */
  private suspend fun postForm(vararg fields: Pair<String, String>): Response =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(TOKEN_ENDPOINT)
        .post(DebridHttp.form(*fields))
        .header("Accept", "application/json")
        .build()
      DebridHttp.client.newCall(request).execute().use { response ->
        Response(response.body?.string().orEmpty(), response.isSuccessful)
      }
    }

  private fun errorMessageFor(json: org.json.JSONObject): String? =
    sequenceOf("error_description", "message", "error")
      .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } }
      .firstOrNull()
}
