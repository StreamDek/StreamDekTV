package com.streamdek.tv.nativeapp.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.nativeapp.data.AppReleaseManifest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class AppUpdateUiState(
    val autoCheckEnabled: Boolean = true,
    val isChecking: Boolean = false,
    val availableRelease: AppReleaseManifest? = null,
    val showPrompt: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val statusText: String? = null,
    val blockedByUnknownSources: Boolean = false,
    val isInstalling: Boolean = false,
    val errorMessage: String? = null,
    val hasCheckedOnce: Boolean = false,
)

class AppUpdateManager(
    private val context: Context,
    private val repository: StreamDekRepository,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("streamdek_updates", Context.MODE_PRIVATE)

    private val autoCheckEnabledKey = "auto_check_enabled"
    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState: StateFlow<AppUpdateUiState> = _uiState

    init {
        _uiState.value = _uiState.value.copy(
            autoCheckEnabled = preferences.getBoolean(autoCheckEnabledKey, true),
        )
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(autoCheckEnabledKey, enabled).apply()
        _uiState.value = _uiState.value.copy(autoCheckEnabled = enabled)
    }

    suspend fun runAutomaticCheck() {
        val enabled = _uiState.value.autoCheckEnabled
        checkForUpdates(showPromptOnAvailable = enabled, force = true, mandatoryOnly = !enabled)
    }

    suspend fun checkForUpdates(showPromptOnAvailable: Boolean = true, force: Boolean = false, mandatoryOnly: Boolean = false) {
        val current = _uiState.value
        if (current.isChecking || (current.hasCheckedOnce && !force)) return

        _uiState.value = current.copy(isChecking = true, errorMessage = null, statusText = "Checking for updates...")
        val release = runCatching { repository.fetchLatestAppRelease() }.getOrNull()
        val updateAvailable = release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        val isMandatory = updateAvailable?.required == true || (
            updateAvailable?.minSupportedVersionCode?.let { BuildConfig.VERSION_CODE < it } == true
        )

        _uiState.value = when {
            updateAvailable != null && (!mandatoryOnly || isMandatory) -> current.copy(
                autoCheckEnabled = _uiState.value.autoCheckEnabled,
                isChecking = false,
                availableRelease = updateAvailable,
                showPrompt = isMandatory || showPromptOnAvailable,
                downloadProgressPercent = null,
                statusText = "Version ${updateAvailable.versionName} is available.",
                blockedByUnknownSources = false,
                isInstalling = false,
                errorMessage = null,
                hasCheckedOnce = true,
            )
            release != null -> current.copy(
                autoCheckEnabled = _uiState.value.autoCheckEnabled,
                isChecking = false,
                availableRelease = null,
                showPrompt = false,
                downloadProgressPercent = null,
                statusText = "You're on the latest version.",
                blockedByUnknownSources = false,
                isInstalling = false,
                errorMessage = null,
                hasCheckedOnce = true,
            )
            else -> current.copy(
                autoCheckEnabled = _uiState.value.autoCheckEnabled,
                isChecking = false,
                downloadProgressPercent = null,
                isInstalling = false,
                statusText = null,
                errorMessage = "Update check failed.",
                hasCheckedOnce = true,
            )
        }
    }

    fun dismissPrompt() {
        _uiState.value = _uiState.value.copy(showPrompt = false)
    }

    fun openUnknownSourcesSettings() {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallbackIntent) }
        }
    }

    suspend fun startUpdate() {
        val release = _uiState.value.availableRelease ?: return
        if (!canInstallUnknownApps()) {
            _uiState.value = _uiState.value.copy(
                blockedByUnknownSources = true,
                showPrompt = true,
                statusText = "Allow installs from StreamDek TV to continue.",
                errorMessage = null,
            )
            openUnknownSourcesSettings()
            return
        }

        _uiState.value = _uiState.value.copy(
            blockedByUnknownSources = false,
            isInstalling = true,
            errorMessage = null,
            statusText = "Downloading ${release.versionName}...",
        )

        val apkFile = runCatching { downloadReleaseApk(release) }.getOrElse { error ->
            _uiState.value = _uiState.value.copy(
                isInstalling = false,
                downloadProgressPercent = null,
                errorMessage = error.message ?: "Update download failed.",
                statusText = null,
            )
            return
        }

        launchPackageInstaller(apkFile)
        _uiState.value = _uiState.value.copy(
            isInstalling = false,
            downloadProgressPercent = null,
            showPrompt = false,
            statusText = "Installer opened for ${release.versionName}.",
            errorMessage = null,
        )
    }

    private fun canInstallUnknownApps(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    private suspend fun downloadReleaseApk(release: AppReleaseManifest): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkName = release.assetName?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "streamdek-tv-${release.versionCode}.apk"
        val apkFile = File(updatesDir, apkName)
        if (apkFile.exists() && verifyChecksumIfPresent(apkFile, release.checksumSha256)) {
            return@withContext apkFile
        }

        val request = Request.Builder().url(resolveReleaseUrl(release.apkUrl)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Update download failed with code ${response.code}.")
            }

            val body = response.body ?: throw IllegalStateException("Update download returned no file.")
            val totalBytes = body.contentLength()
            val tempFile = File(apkFile.parentFile, "${apkFile.name}.part")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    var written = 0L
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (totalBytes > 0) {
                            val percent = ((written * 100) / totalBytes).toInt().coerceIn(0, 100)
                            _uiState.value = _uiState.value.copy(downloadProgressPercent = percent)
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }

            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }
        }

        if (!verifyChecksumIfPresent(apkFile, release.checksumSha256)) {
            apkFile.delete()
            throw IllegalStateException("Downloaded update failed checksum validation.")
        }

        val packageMatches = release.packageName.isNullOrBlank() || packageArchivePackageName(apkFile) == release.packageName
        if (!packageMatches) {
            apkFile.delete()
            throw IllegalStateException("Downloaded update package name did not match this TV app.")
        }

        apkFile
    }

    private fun verifyChecksumIfPresent(file: File, expectedSha256: String?): Boolean {
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expected
    }

    private fun resolveReleaseUrl(rawUrl: String): String {
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
        val baseUrl = BuildConfig.STREAMDEK_API_URL.trimEnd('/')
        return if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else "$baseUrl/$rawUrl"
    }

    private fun launchPackageInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun packageArchivePackageName(file: File): String? {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        return info?.packageName
    }
}
