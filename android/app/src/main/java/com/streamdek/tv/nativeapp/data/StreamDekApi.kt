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
import java.util.UUID

class AuthSessionStore(
    context: Context,
    private val gson: Gson = Gson(),
) {
    private val preferences = context.getSharedPreferences("streamdek_tv_native", Context.MODE_PRIVATE)
    private val authKey = "streamdek_tv_auth_session_v1"
    private val sessionIdKey = "streamdek_tv_session_id"
    private val deviceIdKey = "streamdek_tv_device_id"
    private val activeProfileIdKey = "streamdek_tv_active_profile_id"
    private val preferredStreamKeyPrefix = "streamdek_tv_preferred_stream_v1"

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

    fun sessionId(): String = readOrCreate(sessionIdKey)

    fun deviceId(): String = readOrCreate(deviceIdKey)

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

    private fun readOrCreate(key: String): String {
        val existing = preferences.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(key, created).apply()
        return created
    }

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
            .header("x-device-name", "Android TV")
            .header("x-device-type", "tv")
            .header("x-app-version", BuildConfig.VERSION_NAME)

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
