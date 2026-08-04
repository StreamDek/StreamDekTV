package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal object StreamDekDeviceIdentity {
    fun stableDeviceId(context: Context, type: String): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: Build.FINGERPRINT
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("streamdek:$type:$androidId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "sd-$type-${digest.take(32)}"
    }

    fun sessionId(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("streamdek-session:$deviceId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "session-${digest.take(32)}"
    }

    fun displayName(deviceId: String): String {
        val maker = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.trim()
        val base = listOf(maker, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android TV" }
        return "$base [${deviceId.takeLast(6).uppercase()}]"
    }
}

private const val FIRE_TV_HANDOFF_ALGORITHM = "RSA-OAEP-256-MGF1-SHA1+A256GCM"
private const val LEGACY_HANDOFF_ALGORITHM = "RSA-OAEP-256+A256GCM"

internal fun handoffMgf1Digest(algorithm: String): MGF1ParameterSpec = when (algorithm) {
    FIRE_TV_HANDOFF_ALGORITHM -> MGF1ParameterSpec.SHA1
    LEGACY_HANDOFF_ALGORITHM -> MGF1ParameterSpec.SHA256
    else -> throw IllegalArgumentException("This handoff uses an unsupported encryption format.")
}

internal object HandoffCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "streamdek_handoff_rsa_v1"
    private val aad = "streamdek-handoff-v1".toByteArray(Charsets.UTF_8)

    @Volatile
    private var cachedAlias: String? = null

    fun publicKeyBase64(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = resolveUsableAlias(keyStore)
        val certificate = keyStore.getCertificate(alias)
            ?: throw IllegalStateException("The TV handoff key is unavailable.")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(certificate.publicKey.encoded)
    }

    fun decryptPayload(envelope: EncryptedHandoffPayload): String {
        require(envelope.version == 1) { "This handoff uses an unsupported encryption format." }
        val mgf1Digest = handoffMgf1Digest(envelope.algorithm)
        val decoder = Base64.getUrlDecoder()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = resolveUsableAlias(keyStore)
        val privateKey = keyStore.getKey(alias, null)
            ?: throw IllegalStateException("The TV handoff key is unavailable.")
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec("SHA-256", "MGF1", mgf1Digest, PSource.PSpecified.DEFAULT),
        )
        val aesKey = rsa.doFinal(decoder.decode(envelope.encryptedKey))
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, decoder.decode(envelope.iv)))
        aes.updateAAD(aad)
        return aes.doFinal(decoder.decode(envelope.ciphertext)).toString(Charsets.UTF_8)
    }

    /**
     * Returns an alias whose private key is actually loadable. Some keystore backends can leave
     * an alias present-but-dangling (post backup/restore, or a flaky keymaster). Reusing the same
     * alias for a delete-then-generate in that state has been observed to race on-device (Fire TV),
     * so a broken alias is abandoned in favor of a freshly named one rather than recreated in place.
     */
    private fun resolveUsableAlias(keyStore: KeyStore): String {
        cachedAlias?.takeIf { isUsable(keyStore, it) }?.let { return it }
        val existingUsable = keyStore.aliases().toList()
            .firstOrNull { it.startsWith(KEY_ALIAS_PREFIX) && isUsable(keyStore, it) }
        if (existingUsable != null) {
            cachedAlias = existingUsable
            return existingUsable
        }
        val newAlias = "$KEY_ALIAS_PREFIX-${System.currentTimeMillis()}"
        generateKeyPair(newAlias)
        cachedAlias = newAlias
        // Best-effort cleanup of stale aliases; failures here must never block handoff.
        keyStore.aliases().toList()
            .filter { it.startsWith(KEY_ALIAS_PREFIX) && it != newAlias }
            .forEach { stale -> runCatching { keyStore.deleteEntry(stale) } }
        return newAlias
    }

    private fun isUsable(keyStore: KeyStore, alias: String): Boolean =
        keyStore.containsAlias(alias) && runCatching { keyStore.getKey(alias, null) }.getOrNull() != null

    private fun generateKeyPair(alias: String) {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
                generator.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .build(),
                )
                generator.generateKeyPair()
                return
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) Thread.sleep(150L * (attempt + 1))
            }
        }
        throw IllegalStateException("The TV handoff key is unavailable.", lastError)
    }
}