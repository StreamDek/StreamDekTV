package com.streamdek.tv.nativeapp.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthSessionStore(
    context: Context,
    private val gson: Gson = Gson(),
) {
    internal val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("streamdek_tv_native", Context.MODE_PRIVATE)
    private val authKey = "streamdek_tv_auth_session_v1"
    private val deviceIdKey = "streamdek_tv_device_id"
    private val previousDeviceIdKey = "streamdek_tv_previous_device_id"
    private val activeProfileIdKey = "streamdek_tv_active_profile_id"
    private val rememberLastProfileAtStartupKey = "streamdek_tv_remember_last_profile_at_startup"
    private val preferredStreamKeyPrefix = "streamdek_tv_preferred_stream_v1"
    private val rememberedSourceKeyPrefix = "streamdek_tv_remembered_source_v1"
    private val favouriteChannelsKeyPrefix = "streamdek_tv_favourite_channels_v1"
    private val handoffPublicKeyKey = "streamdek_tv_handoff_public_key_v1"
    private val subtitleFontSizeKey = "streamdek_tv_subtitle_font_size_v1"
    private val subtitlePositionKey = "streamdek_tv_subtitle_position_v1"

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

    /**
     * Subtitle size and placement, adjusted from the player and kept for the next video.
     *
     * Deliberately local to this device rather than part of the synced preferences: how large
     * captions need to be depends on the panel and how far away the sofa is, which no other device
     * on the account shares. The defaults are
     * mpv's own, so a viewer who never touches these sees exactly what they saw before.
     */
    fun subtitleFontSize(): Int = preferences.getInt(subtitleFontSizeKey, 55).coerceIn(28, 84)

    fun saveSubtitleFontSize(size: Int) {
        preferences.edit().putInt(subtitleFontSizeKey, size.coerceIn(28, 84)).apply()
    }

    fun subtitlePosition(): Int = preferences.getInt(subtitlePositionKey, 92).coerceIn(50, 110)

    fun saveSubtitlePosition(position: Int) {
        preferences.edit().putInt(subtitlePositionKey, position.coerceIn(50, 110)).apply()
    }

    fun activeProfileId(): String? = preferences.getString(activeProfileIdKey, null)

    fun setActiveProfileId(profileId: String?) {
        preferences.edit().putString(activeProfileIdKey, profileId).apply()
    }

    /** TV-local because this controls the startup experience of this television only. */
    fun rememberLastProfileAtStartup(): Boolean =
        preferences.getBoolean(rememberLastProfileAtStartupKey, false)

    fun setRememberLastProfileAtStartup(remember: Boolean) {
        preferences.edit().putBoolean(rememberLastProfileAtStartupKey, remember).apply()
    }

    fun loadFavouriteChannels(): List<MediaItem> {
        val raw = preferences.getString(favouriteChannelsStorageKey(), null) ?: return emptyList()
        val type = object : TypeToken<List<MediaItem>>() {}.type
        return runCatching {
            gson.fromJson<List<MediaItem>>(raw, type).orEmpty().map { item ->
                // Gson populates fields directly via reflection, bypassing the Kotlin default
                // value, so legacy-stored entries missing this key deserialize to a literal null
                // despite the non-nullable type — normalize it back before it reaches anything
                // that trusts the type and crashes on the null.
                val safeHeaders = (item.requestHeaders as Map<String, String>?) ?: emptyMap()
                if (safeHeaders === item.requestHeaders) item else item.copy(requestHeaders = safeHeaders)
            }
        }.getOrDefault(emptyList())
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

    /**
     * The handoff key itself is still read straight from the keystore on every call so a
     * regenerated key is picked up immediately. Only the failure path changed: a keystore that
     * momentarily refuses to hand back the certificate used to abort the whole request, taking
     * every unrelated API call down with it. The last key we successfully published is kept as a
     * fallback so the app stays usable, and handoff keeps working as long as that key is current.
     */
    fun handoffPublicKey(): String? = runCatching { HandoffCrypto.publicKeyBase64() }
        .onSuccess { key ->
            if (key != preferences.getString(handoffPublicKeyKey, null)) {
                preferences.edit().putString(handoffPublicKeyKey, key).apply()
            }
        }
        .getOrElse { error ->
            TvDebugLogger.w("Handoff", "public key unavailable; using last published key", error)
            preferences.getString(handoffPublicKeyKey, null)?.takeIf { it.isNotBlank() }
        }

    fun preferredStreamKey(mediaType: String, mediaId: String, episodeKey: String?): String? {
        return preferences.getString(streamPreferenceStorageKey(mediaType, mediaId, episodeKey), null)
    }

    fun savePreferredStreamKey(mediaType: String, mediaId: String, episodeKey: String?, streamKey: String?) {
        val storageKey = streamPreferenceStorageKey(mediaType, mediaId, episodeKey)
        preferences.edit().apply {
            if (streamKey.isNullOrBlank()) remove(storageKey) else putString(storageKey, streamKey)
        }.apply()
    }

    /**
     * The resolved source a title last played from. See [RememberedPlaybackSource].
     *
     * Stored beside the preferred stream key rather than replacing it: the key still does useful
     * work when a full resolve does have to run, because it is what pushes the viewer's choice to
     * the top of the ranking.
     */
    fun rememberedPlaybackSource(mediaType: String, mediaId: String, episodeKey: String?): RememberedPlaybackSource? {
        val raw = preferences.getString(rememberedSourceStorageKey(mediaType, mediaId, episodeKey), null) ?: return null
        return runCatching { gson.fromJson(raw, RememberedPlaybackSource::class.java) }.getOrNull()
            ?.takeIf { it.url.isNotBlank() }
            // Gson writes fields by reflection and so walks straight past Kotlin's default values.
            // An entry stored before a field existed comes back with a literal null in a
            // non-nullable slot, which then blows up somewhere far away from here.
            ?.let { source ->
                @Suppress("USELESS_CAST")
                val safeHeaders = (source.requestHeaders as Map<String, String>?) ?: emptyMap()
                if (safeHeaders === source.requestHeaders) source else source.copy(requestHeaders = safeHeaders)
            }
    }

    fun saveRememberedPlaybackSource(
        mediaType: String,
        mediaId: String,
        episodeKey: String?,
        source: RememberedPlaybackSource?,
    ) {
        val storageKey = rememberedSourceStorageKey(mediaType, mediaId, episodeKey)
        preferences.edit().apply {
            if (source == null) remove(storageKey) else putString(storageKey, gson.toJson(source))
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

    private fun rememberedSourceStorageKey(mediaType: String, mediaId: String, episodeKey: String?): String {
        val profile = activeProfileId() ?: "default"
        return listOf(rememberedSourceKeyPrefix, profile, mediaType, mediaId, episodeKey.orEmpty()).joinToString(":")
    }
}

/** How well the backend is currently answering, so screens can explain themselves to the viewer. */
enum class ApiReachability {
    /** The last request succeeded against the network. */
    Online,

    /** The network is unreachable but cached content is still being served. */
    Cached,

    /** The network is unreachable and nothing was cached. */
    Offline,
}

/**
 * Shared HTTP client. A disk cache is what makes the offline fallback possible: successful GETs
 * are stored, and when the backend cannot be reached the same request is replayed against the
 * cache so the viewer keeps the last good screen instead of an empty one.
 */
object StreamDekHttp {
    private const val CACHE_BYTES = 24L * 1024 * 1024

    @Volatile
    private var cache: okhttp3.Cache? = null

    @Volatile
    private var instance: OkHttpClient? = null

    fun client(context: Context): OkHttpClient = instance ?: synchronized(this) {
        instance ?: build(context).also { instance = it }
    }

    /** Dropped on sign-out and profile switches so one viewer never sees another's cached rows. */
    fun evictCache() {
        runCatching { cache?.evictAll() }
    }

    private fun build(context: Context): OkHttpClient {
        val httpCache = okhttp3.Cache(java.io.File(context.applicationContext.cacheDir, "streamdek-http"), CACHE_BYTES)
        cache = httpCache
        return OkHttpClient.Builder()
            .dns(StreamDekDns(context.applicationContext))
            .cache(httpCache)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Most backend routes send no caching headers, so nothing would ever be stored and the
            // offline replay below would always miss. Responses are given a short lifetime purely
            // so they land in the cache; live requests always revalidate (see `noCache` below).
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().method != "GET" || response.header("Cache-Control") != null) {
                    response
                } else {
                    response.newBuilder().header("Cache-Control", "private, max-age=300").build()
                }
            }
            .build()
    }
}

class StreamDekApi(
    @PublishedApi internal val sessionStore: AuthSessionStore,
    @PublishedApi internal val client: OkHttpClient = StreamDekHttp.client(sessionStore.appContext),
    @PublishedApi internal val gson: Gson = Gson(),
    @PublishedApi internal val baseUrl: String = BuildConfig.STREAMDEK_API_URL.trimEnd('/'),
) {
    @PublishedApi internal val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * The viewer's own content-service keys, for the ones they chose to keep on this television.
     *
     * Held on the client rather than passed per call because attaching them is [buildRequest]'s
     * job: TMDB requests are issued from a dozen places in the repository, and a key that has to
     * be threaded through each of them is a key half of them will forget.
     */
    val serviceCredentials: ServiceCredentialManager = ServiceCredentialManager(sessionStore.appContext)

    private val _reachability = MutableStateFlow(ApiReachability.Online)

    /** Drives the "showing saved content" notice; never blocks a screen on its own. */
    val reachability: StateFlow<ApiReachability> = _reachability

    private val _sessionExpired = MutableStateFlow(false)

    /** Set once the backend has rejected the stored credentials, so the shell can ask for sign-in. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired

    /**
     * Bumped whenever a request ends without a usable answer. Callers that fan out across many
     * endpoints compare the value before and after to tell "the backend returned nothing" from
     * "the backend was never reached", which are very different things to show a viewer.
     */
    @Volatile
    var failureEpoch: Long = 0L
        private set

    fun clearSessionExpired() {
        _sessionExpired.value = false
    }

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
    ): T? {
        val raw = executeRaw(method, path, body, session) ?: return null
        val type = object : TypeToken<T>() {}.type
        return runCatching {
            gson.fromJson<T>(raw, type)
        }.onFailure {
            noteParseFailure(path, raw, it)
        }.getOrNull()
    }

    @PublishedApi
    internal fun noteParseFailure(path: String, raw: String, error: Throwable) {
        failureEpoch++
        TvDebugLogger.e("Api", "json parse failed path=$path payload=${raw.take(240)}", error)
    }

    /**
     * Reads the platform's content policy and hands it to [AdultContentFilter].
     *
     * Needs no session: the block applies before anyone signs in. A failure leaves the filter on
     * rather than reporting an error, because the safe state and the unknown state are the same.
     */
    internal suspend fun refreshContentPolicy() {
        runCatching {
            val raw = executeRaw("GET", "/public/content-policy", null, null)
                ?: error("Empty content policy response")
            val json = org.json.JSONObject(raw)
            val terms = json.optJSONArray("terms")
            AdultContentFilter.applyPolicy(
                blockAdult = json.optBoolean("blockAdult", true),
                terms = buildList {
                    if (terms != null) for (index in 0 until terms.length()) {
                        terms.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                },
            )
        }.onFailure { AdultContentFilter.applyPolicy(null, null) }
    }

    /**
     * Runs one call and returns its body, or null when there is nothing usable to parse.
     *
     * GETs are safe to repeat, so they get a small retry budget for transport errors and for the
     * 429/5xx answers a restarting backend produces, then fall back to the disk cache. Writes are
     * never retried — a duplicated watchlist add or progress write is worse than a failed one.
     */
    @PublishedApi
    internal suspend fun executeRaw(
        method: String,
        path: String,
        body: String?,
        session: AuthSession?,
    ): String? = withContext(Dispatchers.IO) {
        TvDebugLogger.i(
            "Api",
            "request method=$method path=$path auth=${session != null} profile=${sessionStore.activeProfileId() ?: "none"}",
        )
        val idempotent = method == "GET"
        val attempts = if (idempotent) MAX_GET_ATTEMPTS else 1
        var transportError: IOException? = null

        for (attempt in 1..attempts) {
            val response = runCatching { client.newCall(buildRequest(method, path, body, session)).execute() }
                .getOrElse { error ->
                    if (error !is IOException) throw error
                    transportError = error
                    TvDebugLogger.w("Api", "transport failure method=$method path=$path attempt=$attempt", error)
                    null
                }

            if (response == null) {
                if (attempt < attempts) delay(retryDelayMs(attempt))
                continue
            }

            response.use {
                if (it.isSuccessful) {
                    val raw = it.body?.string()?.takeIf { payload -> payload.isNotBlank() }
                        // A successful mutation is allowed to return 204/empty. Give typed write
                        // callers an empty JSON object so they can distinguish success from a
                        // failed request instead of reporting that Remove did nothing.
                        ?: if (!idempotent) "{}" else null
                    if (raw == null) {
                        failureEpoch++
                    } else {
                        _reachability.value = ApiReachability.Online
                        TvDebugLogger.d("Api", "response method=$method path=$path code=${it.code} bytes=${raw.length}")
                    }
                    return@withContext raw
                }

                val errorBody = runCatching { it.body?.string() }.getOrNull().orEmpty()
                TvDebugLogger.w(
                    "Api",
                    "response method=$method path=$path code=${it.code} body=${errorBody.take(240)}",
                )
                if (it.code == 401 && session != null) confirmCredentialsRejected(path, session)
                val retryable = idempotent && (it.code == 429 || it.code in 500..599)
                if (!retryable || attempt == attempts) {
                    failureEpoch++
                    // A reachable backend that is refusing the request is not an offline device;
                    // leave the reachability state alone so the viewer is not told they are offline.
                    return@withContext null
                }
            }
            delay(retryDelayMs(attempt))
        }

        failureEpoch++
        val cached = transportError?.let { if (idempotent) readFromCache(method, path, body, session) else null }
        _reachability.value = if (cached != null) ApiReachability.Cached else ApiReachability.Offline
        cached
    }

    /**
     * Replays a GET against the disk cache alone. Staleness is deliberately unbounded: an hour-old
     * home screen is a far better answer than a blank one when the network is gone.
     */
    private fun readFromCache(method: String, path: String, body: String?, session: AuthSession?): String? =
        runCatching {
            val request = buildRequest(method, path, body, session)
                .newBuilder()
                .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(Int.MAX_VALUE, TimeUnit.SECONDS).build())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull().also {
            if (it != null) TvDebugLogger.i("Api", "served from cache path=$path bytes=${it.length}")
        }

    /**
     * A 401 does not always mean the sign-in has lapsed. The tracking-service routes answer 401
     * for "this profile has not connected that service", which is an ordinary state and must never
     * sign anybody out. Anything else is confirmed against `/auth/me` before the shell is told.
     */
    private suspend fun confirmCredentialsRejected(path: String, session: AuthSession) {
        if (TRACKING_SERVICE_PREFIXES.any(path::startsWith)) {
            TvDebugLogger.i("Api", "tracking service not connected path=$path")
            return
        }
        if (path == AUTH_PROBE_PATH || _sessionExpired.value || !authProbeInFlight.compareAndSet(false, true)) return
        try {
            val probe = runCatching { client.newCall(buildRequest("GET", AUTH_PROBE_PATH, null, session)).execute() }
                .getOrNull() ?: return
            val rejected = probe.use { it.code == 401 || it.code == 403 }
            if (rejected) {
                TvDebugLogger.w("Api", "sign-in no longer valid; first seen on path=$path")
                _sessionExpired.value = true
            }
        } finally {
            authProbeInFlight.set(false)
        }
    }

    private fun buildRequest(method: String, path: String, body: String?, session: AuthSession?): Request {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
            // Always revalidate; the cache exists only for the offline replay above.
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("x-client-session-id", sessionStore.sessionId())
            .header("x-client-device-id", sessionStore.deviceId())
            .header("x-client-name", "StreamDek TV")
            .header("x-client-platform", "android-tv")
            .header("x-device-name", sessionStore.deviceName())
            .header("x-device-type", "tv")
            .header("x-app-version", BuildConfig.VERSION_NAME)
        sessionStore.handoffPublicKey()?.let { builder.header("x-handoff-public-key", it) }
        sessionStore.previousDeviceId()?.let { builder.header("x-previous-device-id", it) }

        // A key the viewer chose to keep on this television still has to reach the backend, since
        // the backend is what talks to TMDB. It travels with the request that needs it, over TLS,
        // and is never stored server-side -- which is precisely what "this TV only" means, and is
        // said in those words on the screen where the choice is made.
        //
        // Scoped to the paths that actually spend the credential: no add-on, plugin or artwork
        // request has any business carrying one.
        if (path.startsWith("/tmdb/") || path.startsWith("/addons/resolve-id/")) {
            serviceCredentials.requestKey(ContentService.Tmdb)?.let { builder.header("x-tmdb-api-key", it) }
        }
        if (path.startsWith("/mdblist/") || path.startsWith("/sync/")) {
            serviceCredentials.requestKey(ContentService.Mdblist)?.let { builder.header("x-mdblist-api-key", it) }
        }

        if (session != null) {
            builder.header("Authorization", "Bearer ${session.user.accessToken}")
            builder.header("x-user-id", session.user.uid)
            sessionStore.activeProfileId()?.takeIf { it.isNotBlank() }?.let {
                builder.header("x-profile-id", it)
            }
        }

        val requestBody = body?.toRequestBody(jsonMediaType)
        return when (method) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(jsonMediaType))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.get()
        }.build()
    }

    private val authProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val MAX_GET_ATTEMPTS = 3
        private const val AUTH_PROBE_PATH = "/auth/me"
        private val TRACKING_SERVICE_PREFIXES = listOf("/trakt/", "/simkl/", "/mdblist/")

        internal fun retryDelayMs(attempt: Int): Long = 350L * (1L shl (attempt - 1))
    }
}
