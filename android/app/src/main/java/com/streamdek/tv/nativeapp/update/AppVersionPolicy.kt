package com.streamdek.tv.nativeapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class UpdateMode { NONE, OPTIONAL, RECOMMENDED, REQUIRED }

data class AppVersionPolicy(
    val latestVersion: String,
    val minimumSupportedVersion: String,
    val updateMode: UpdateMode,
    val title: String,
    val message: String,
    val requiredTitle: String,
    val requiredMessage: String,
    val updateUrl: String,
    val releaseNotesUrl: String?,
)

sealed interface AppVersionGateState {
    data object Checking : AppVersionGateState
    data class Ready(val policy: AppVersionPolicy, val effectiveMode: UpdateMode) : AppVersionGateState
    data class Required(val policy: AppVersionPolicy) : AppVersionGateState
    data object Unavailable : AppVersionGateState
}

/** Startup UX gate plus the process-wide landing point for backend HTTP 426 responses. */
object AppVersionPolicyRuntime {
    private const val PREFS = "streamdek_app_version_policy"
    private const val CACHED_POLICY = "cached_policy"
    private const val CACHED_AT = "cached_at"
    private const val MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val _state = MutableStateFlow<AppVersionGateState>(AppVersionGateState.Checking)
    val state: StateFlow<AppVersionGateState> = _state

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val url = BuildConfig.STREAMDEK_API_URL.trimEnd('/') + "/api/v1/public/app-version-policy?platform=android-tv"
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-StreamDek-Platform", "android-tv")
            .header("X-StreamDek-Version", BuildConfig.VERSION_NAME)
            .header("X-StreamDek-Build", BuildConfig.VERSION_CODE.toString())
            .build()
        val json = runCatching {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Policy service returned HTTP ${response.code}" }
                response.body?.string()?.takeIf(String::isNotBlank) ?: error("Empty version policy")
            }
        }.onSuccess { raw ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CACHED_POLICY, raw).putLong(CACHED_AT, System.currentTimeMillis()).apply()
        }.getOrNull() ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
            prefs.getString(CACHED_POLICY, null)?.takeIf {
                System.currentTimeMillis() - prefs.getLong(CACHED_AT, 0L) <= MAX_CACHE_AGE_MS
            }
        }

        val policy = json?.let(::parsePolicy)
        _state.value = if (policy == null) AppVersionGateState.Unavailable else stateFor(policy)
    }

    fun acceptUnsupportedResponse(rawBody: String) {
        val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: return
        val code = json.optJSONObject("error")?.optString("code")
            ?: json.optJSONObject("errorDetail")?.optString("code")
        if (code != "CLIENT_VERSION_UNSUPPORTED") return
        val current = when (val state = _state.value) {
            is AppVersionGateState.Ready -> state.policy
            is AppVersionGateState.Required -> state.policy
            else -> null
        }
        val policy = AppVersionPolicy(
            latestVersion = json.optString("latestVersion", current?.latestVersion ?: ""),
            minimumSupportedVersion = json.optString("minimumSupportedVersion", current?.minimumSupportedVersion ?: ""),
            updateMode = UpdateMode.REQUIRED,
            title = current?.title ?: "Update required",
            message = current?.message ?: "",
            requiredTitle = "Update required",
            requiredMessage = json.optString("message", "This version of StreamDek is no longer supported. Update to continue."),
            updateUrl = json.optString("updateUrl", current?.updateUrl ?: ""),
            releaseNotesUrl = current?.releaseNotesUrl,
        )
        _state.value = AppVersionGateState.Required(policy)
    }

    fun openUpdate(context: Context, policy: AppVersionPolicy) {
        if (policy.updateUrl.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policy.updateUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun stateFor(policy: AppVersionPolicy): AppVersionGateState {
        val minimum = compareVersions(BuildConfig.VERSION_NAME, policy.minimumSupportedVersion)
        if (minimum == null || minimum < 0) return AppVersionGateState.Required(policy)
        val latest = compareVersions(BuildConfig.VERSION_NAME, policy.latestVersion)
        val mode = if (latest != null && latest < 0) policy.updateMode else UpdateMode.NONE
        return if (mode == UpdateMode.REQUIRED) AppVersionGateState.Required(policy) else AppVersionGateState.Ready(policy, mode)
    }

    private fun parsePolicy(raw: String): AppVersionPolicy? = runCatching {
        val json = JSONObject(raw)
        AppVersionPolicy(
            latestVersion = json.getString("latestVersion"),
            minimumSupportedVersion = json.getString("minimumSupportedVersion"),
            updateMode = UpdateMode.valueOf(json.optString("updateMode", "OPTIONAL").uppercase()),
            title = json.optString("title", "Update available"),
            message = json.optString("message", "A newer version of StreamDek is available."),
            requiredTitle = json.optString("requiredTitle", "Update required"),
            requiredMessage = json.optString("requiredMessage", "This version is no longer supported. Update to continue."),
            updateUrl = json.optString("updateUrl"),
            releaseNotesUrl = json.optString("releaseNotesUrl").takeIf(String::isNotBlank),
        ).also {
            require(compareVersions(it.minimumSupportedVersion, it.latestVersion)?.let { order -> order <= 0 } == true)
        }
    }.getOrNull()
}

internal fun compareVersions(left: String, right: String): Int? {
    data class Version(val core: List<Long>, val hotfix: Int, val prerelease: List<String>)
    fun parse(value: String): Version? {
        val input = value.trim()
        if (input.isEmpty() || input.length > 64) return null
        val withoutBuild = input.substringBefore('+')
        val coreText = withoutBuild.substringBefore('-')
        val match = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)([A-Za-z]?)$").matchEntire(coreText) ?: return null
        val core = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
        val hotfix = match.groupValues[4].lowercase().firstOrNull()?.let { it.code - 'a'.code + 1 } ?: 0
        val prerelease = withoutBuild.substringAfter('-', "").takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
        if (hotfix > 0 && prerelease.isNotEmpty()) return null
        if (prerelease.any { it.isEmpty() || !it.matches(Regex("[0-9A-Za-z-]+")) }) return null
        return Version(core, hotfix, prerelease)
    }
    val a = parse(left) ?: return null
    val b = parse(right) ?: return null
    a.core.zip(b.core).firstOrNull { it.first != it.second }?.let { (x, y) -> return x.compareTo(y) }
    if (a.hotfix != b.hotfix) return a.hotfix.compareTo(b.hotfix)
    if (a.prerelease.isEmpty() || b.prerelease.isEmpty()) {
        return when {
            a.prerelease.isEmpty() && b.prerelease.isEmpty() -> 0
            a.prerelease.isEmpty() -> 1
            else -> -1
        }
    }
    repeat(maxOf(a.prerelease.size, b.prerelease.size)) { index ->
        val av = a.prerelease.getOrNull(index) ?: return -1
        val bv = b.prerelease.getOrNull(index) ?: return 1
        if (av == bv) return@repeat
        val an = av.all(Char::isDigit)
        val bn = bv.all(Char::isDigit)
        return when {
            an && bn -> (av.toLongOrNull() ?: return null).compareTo(bv.toLongOrNull() ?: return null)
            an -> -1
            bn -> 1
            else -> av.compareTo(bv)
        }
    }
    return 0
}
