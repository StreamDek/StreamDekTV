package com.streamdek.tv.nativeapp.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AuthSessionStore(
    context: Context,
    private val gson: Gson = Gson(),
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("streamdek_tv_native", Context.MODE_PRIVATE)
    private val authKey = "streamdek_tv_auth_session_v1"
    private val deviceIdKey = "streamdek_tv_device_id"
    private val previousDeviceIdKey = "streamdek_tv_previous_device_id"
    private val activeProfileIdKey = "streamdek_tv_active_profile_id"
    private val preferredStreamKeyPrefix = "streamdek_tv_preferred_stream_v1"
    private val favouriteChannelsKeyPrefix = "streamdek_tv_favourite_channels_v1"

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<AuthSession?> = _session

    fun currentSession(): AuthSession? = _session.value

    fun saveSession(next: AuthSession) {
        preferences.edit().putString(authKey, gson.toJson(next)).apply()
        _session.value = next
    }

    fun clearSession() {
        preferences.edit().remove(authKey).remove(activeProfileIdKey).apply()
        _session.value = null
    }

    fun activeProfileId(): String? = preferences.getString(activeProfileIdKey, null)

    fun setActiveProfileId(profileId: String?) {
        preferences.edit().putString(activeProfileIdKey, profileId).apply()
    }

    fun loadFavouriteChannels(): List<MediaItem> {
        val raw = preferences.getString(favouriteChannelsStorageKey(), null) ?: return emptyList()
        val type = object : TypeToken<List<MediaItem>>() {}.type
        return runCatching { gson.fromJson<List<MediaItem>>(raw, type).orEmpty() }.getOrDefault(emptyList())
    }

    fun saveFavouriteChannels(items: List<MediaItem>) {
        preferences.edit().putString(favouriteChannelsStorageKey(), gson.toJson(items)).apply()
    }

    fun favouriteOwnerKey(): String {
        val userId = currentSession()?.user?.uid
        val profileId = activeProfileId()
        return when {
            userId.isNullOrBlank() -> profileId?.let { "guest:$it" } ?: "guest"
            profileId.isNullOrBlank() -> userId
            else -> "$userId:$profileId"
        }
    }
    fun deviceId(): String {
        val stable = StreamDekDeviceIdentity.stableDeviceId(appContext, "tv")
        val stored = preferences.getString(deviceIdKey, null)?.takeIf { it.isNotBlank() }
        if (stored != null && stored != stable && preferences.getString(previousDeviceIdKey, null).isNullOrBlank()) {
            preferences.edit().putString(previousDeviceIdKey, stored).apply()
        }
        if (stored != stable) preferences.edit().putString(deviceIdKey, stable).apply()
        return stable
    }

    fun previousDeviceId(): String? = preferences.getString(previousDeviceIdKey, null)
        ?.takeIf { it.isNotBlank() && it != deviceId() }

    fun sessionId(): String = StreamDekDeviceIdentity.sessionId(deviceId())

    fun deviceName(): String = StreamDekDeviceIdentity.displayName(deviceId())

    fun handoffPublicKey(): String = HandoffCrypto.publicKeyBase64()

    fun preferredStreamKey(mediaType: String, mediaId: String, episodeKey: String?): String? {
        return preferences.getString(streamPreferenceStorageKey(mediaType, mediaId, episodeKey), null)
    }

    fun savePreferredStreamKey(mediaType: String, mediaId: String, episodeKey: String?, streamKey: String?) {
        val storageKey = streamPreferenceStorageKey(mediaType, mediaId, episodeKey)
        preferences.edit().apply {
            if (streamKey.isNullOrBlank()) remove(storageKey) else putString(storageKey, streamKey)
        }.apply()
    }

    private fun loadSession(): AuthSession? {
        val raw = preferences.getString(authKey, null) ?: return null
        return runCatching { gson.fromJson(raw, AuthSession::class.java) }.getOrNull()
    }

    private fun favouriteChannelsStorageKey(): String = "$favouriteChannelsKeyPrefix:${favouriteOwnerKey()}"
    private fun streamPreferenceStorageKey(mediaType: String, mediaId: String, episodeKey: String?): String {
        val profile = activeProfileId() ?: "default"
        return listOf(preferredStreamKeyPrefix, profile, mediaType, mediaId, episodeKey.orEmpty()).joinToString(":")
    }
}

class StreamDekApi(
    @PublishedApi internal val sessionStore: AuthSessionStore,
    @PublishedApi internal val client: OkHttpClient = OkHttpClient(),
    @PublishedApi internal val gson: Gson = Gson(),
    @PublishedApi internal val baseUrl: String = BuildConfig.STREAMDEK_API_URL.trimEnd('/'),
) {
    @PublishedApi internal val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend inline fun <reified T> get(path: String, session: AuthSession? = sessionStore.currentSession()): T? =
        request("GET", path, session = session)

    suspend inline fun <reified T> post(path: String, body: Any, session: AuthSession? = sessionStore.currentSession()): T? =
        request("POST", path, body = gson.toJson(body), session = session)

    suspend inline fun <reified T> put(path: String, body: Any, session: AuthSession? = sessionStore.currentSession()): T? =
        request("PUT", path, body = gson.toJson(body), session = session)

    suspend inline fun <reified T> patch(path: String, body: Any, session: AuthSession? = sessionStore.currentSession()): T? =
        request("PATCH", path, body = gson.toJson(body), session = session)

    suspend inline fun <reified T> delete(path: String, body: Any? = null, session: AuthSession? = sessionStore.currentSession()): T? =
        request("DELETE", path, body = body?.let(gson::toJson), session = session)

    suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: String? = null,
        session: AuthSession? = sessionStore.currentSession(),
    ): T? = withContext(Dispatchers.IO) {
        TvDebugLogger.i(
            "Api",
            "request method=$method path=$path auth=${session != null} profile=${sessionStore.activeProfileId() ?: "none"}",
        )
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
            .header("x-client-session-id", sessionStore.sessionId())
            .header("x-client-device-id", sessionStore.deviceId())
            .header("x-client-name", "StreamDek TV")
            .header("x-client-platform", "android-tv")
            .header("x-device-name", sessionStore.deviceName())
            .header("x-device-type", "tv")
            .header("x-handoff-public-key", sessionStore.handoffPublicKey())
            .header("x-app-version", BuildConfig.VERSION_NAME)
        sessionStore.previousDeviceId()?.let { builder.header("x-previous-device-id", it) }

        if (session != null) {
            builder.header("Authorization", "Bearer ${session.user.accessToken}")
            builder.header("x-user-id", session.user.uid)
            sessionStore.activeProfileId()?.takeIf { it.isNotBlank() }?.let {
                builder.header("x-profile-id", it)
            }
        }

        val requestBody = body?.toRequestBody(jsonMediaType)
        val request = when (method) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.get()
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                TvDebugLogger.w(
                    "Api",
                    "response method=$method path=$path code=${response.code} body=${errorBody.take(240)}",
                )
                return@withContext null
            }
            val raw = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@withContext null
            TvDebugLogger.d("Api", "response method=$method path=$path code=${response.code} bytes=${raw.length}")
            val type = object : TypeToken<T>() {}.type
            runCatching {
                gson.fromJson<T>(raw, type)
            }.onFailure {
                TvDebugLogger.e("Api", "json parse failed path=$path payload=${raw.take(240)}", it)
            }.getOrNull()
        }
    }
}
