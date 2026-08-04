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
    private const val KEY_ALIAS = "streamdek_handoff_rsa_v1"
    private val aad = "streamdek-handoff-v1".toByteArray(Charsets.UTF_8)

    fun publicKeyBase64(): String {
        ensureKeyPair()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("The TV handoff key is unavailable.")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(certificate.publicKey.encoded)
    }

    fun decryptPayload(envelope: EncryptedHandoffPayload): String {
        require(envelope.version == 1) { "This handoff uses an unsupported encryption format." }
        val mgf1Digest = handoffMgf1Digest(envelope.algorithm)
        ensureKeyPair()
        val decoder = Base64.getUrlDecoder()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
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

    private fun ensureKeyPair() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .build(),
        )
        generator.generateKeyPair()
    }
}