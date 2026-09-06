package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.StringRes
import com.streamdek.tv.R
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The viewer's own TMDB and MDBList keys, on the television.
 *
 * Deliberately the same model as the phone's, down to the precedence rule and the wording of the
 * storage choice: device key first, then the key saved to the StreamDek account, then whatever
 * fallback the deployment still allows. A television that resolved credentials differently from a
 * phone would be a bug nobody could see until a viewer noticed one device had ratings and the
 * other did not.
 *
 * The television's own reason for existing here is the one thing that is different: typing a
 * forty-character key on a remote is miserable, so the account-saved route is the one the setup
 * screen leads with, and entering a key here is offered as the fallback rather than the default.
 */

// ── The services ──────────────────────────────────────────────────────────────────────────────

/**
 * A content service the viewer can bring their own key to.
 *
 * The tagline, the blurb, the bullets and the help text are resource ids rather than text, for the
 * same reason [CredentialStorage] below holds ids: these are read by a person, and a data model
 * carrying English pins them to English on a translated television. The [id] and the [label] stay
 * plain strings - the id is a storage contract and the label is the service's own name, and neither
 * is ever translated. The same enum on the phone is written this way, and the two share the
 * resources so one English sentence cannot become two different French ones.
 */
enum class ContentService(
    val id: String,
    val label: String,
    @StringRes val taglineRes: Int,
    @StringRes val blurbRes: Int,
    val usesRes: List<Int>,
    val keyUrl: String,
    val howToGetRes: List<Int>,
    @StringRes val keyHintRes: Int,
) {
    Tmdb(
        id = "tmdb",
        label = "TMDB",
        taglineRes = R.string.service_tmdb_tagline,
        blurbRes = R.string.service_tmdb_blurb,
        usesRes = listOf(
            R.string.service_tmdb_use_posters,
            R.string.service_tmdb_use_information,
            R.string.service_tmdb_use_cast,
            R.string.service_tmdb_use_seasons,
            R.string.service_tmdb_use_search,
        ),
        keyUrl = "themoviedb.org/settings/api",
        howToGetRes = listOf(
            R.string.service_tmdb_step_account,
            R.string.service_tmdb_step_request,
            R.string.service_tmdb_step_copy,
        ),
        keyHintRes = R.string.service_tmdb_key_hint,
    ),
    Mdblist(
        id = "mdblist",
        label = "MDBList",
        taglineRes = R.string.service_mdblist_tagline,
        blurbRes = R.string.service_mdblist_blurb,
        usesRes = listOf(
            R.string.service_mdblist_use_ratings,
            R.string.service_mdblist_use_extra_ratings,
            R.string.service_mdblist_use_lists,
        ),
        keyUrl = "mdblist.com/preferences",
        howToGetRes = listOf(
            R.string.service_mdblist_step_sign_in,
            R.string.service_mdblist_step_preferences,
            R.string.service_mdblist_step_generate,
        ),
        keyHintRes = R.string.service_mdblist_key_hint,
    ),
    IntroDb(
        id = "introdb",
        label = "IntroDB",
        taglineRes = R.string.service_introdb_tagline,
        blurbRes = R.string.service_introdb_blurb,
        usesRes = listOf(
            R.string.service_introdb_use_timing,
            R.string.service_introdb_use_skipping,
            R.string.service_introdb_use_ending,
        ),
        keyUrl = "introdb.app/account",
        howToGetRes = listOf(
            R.string.service_introdb_step_sign_in,
            R.string.service_introdb_step_copy,
            R.string.service_introdb_step_paste,
        ),
        keyHintRes = R.string.service_introdb_key_hint,
    ),
    TheIntroDb(
        id = "theintrodb",
        label = "TheIntroDB",
        taglineRes = R.string.service_theintrodb_tagline,
        blurbRes = R.string.service_theintrodb_blurb,
        usesRes = listOf(
            R.string.service_theintrodb_use_timing,
            R.string.service_theintrodb_use_skipping,
            R.string.service_theintrodb_use_credits,
        ),
        keyUrl = "theintrodb.org/docs",
        howToGetRes = listOf(
            R.string.service_theintrodb_step_docs,
            R.string.service_theintrodb_step_follow,
            R.string.service_theintrodb_step_copy,
        ),
        keyHintRes = R.string.service_theintrodb_key_hint,
    );

    companion object {
        val all: List<ContentService> = listOf(Tmdb, Mdblist, IntroDb, TheIntroDb)

        fun fromId(value: String?): ContentService? =
            all.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Where a configured key is kept.
 *
 * Resource ids rather than text: this is a data-layer enum and the wording is read by a viewer, so
 * holding the English here would pin those two lines to English on a translated television. The
 * screen resolves them with `stringResource`, which also means they re-read when the language
 * changes rather than being fixed when the enum was first touched.
 */
enum class CredentialStorage(@StringRes val labelRes: Int, @StringRes val detailRes: Int) {
    Device(
        labelRes = R.string.content_services_this_tv_only,
        detailRes = R.string.credential_storage_device_detail,
    ),
    Account(
        labelRes = R.string.content_services_account_storage,
        detailRes = R.string.credential_storage_account_detail,
    ),
}

enum class CredentialStatus { NotConfigured, Checking, Connected, NeedsAttention }

data class ContentServiceState(
    val service: ContentService,
    val status: CredentialStatus = CredentialStatus.NotConfigured,
    val storage: CredentialStorage? = null,
    val maskedKey: String? = null,
    val accountLabel: String? = null,
    /** The account also has a key while this television is using its own. Offered, not hidden. */
    val accountKeyAlsoAvailable: Boolean = false,
) {
    val configured: Boolean
        get() = status == CredentialStatus.Connected || status == CredentialStatus.NeedsAttention

    /**
     * What the settings row says on the right-hand side, in a few words readable from a sofa.
     *
     * A resource id rather than the words themselves: this is a data class, and a summary resolved
     * here would be fixed in whatever language was current when the state was built.
     */
    @get:StringRes
    val summaryRes: Int
        get() = when {
            status == CredentialStatus.NeedsAttention -> R.string.credential_status_needs_attention
            status == CredentialStatus.Connected && storage == CredentialStorage.Account ->
                R.string.credential_status_connected_via_streamdek
            status == CredentialStatus.Connected -> R.string.credential_status_connected_on_this_tv
            else -> R.string.settings_tv_not_configured
        }
}

data class ContentServicesState(
    val tmdb: ContentServiceState = ContentServiceState(ContentService.Tmdb),
    val mdblist: ContentServiceState = ContentServiceState(ContentService.Mdblist),
    val introDb: ContentServiceState = ContentServiceState(ContentService.IntroDb),
    val theIntroDb: ContentServiceState = ContentServiceState(ContentService.TheIntroDb),
    val sharedFallbackAvailable: Boolean = true,
    val loaded: Boolean = false,
) {
    fun of(service: ContentService): ContentServiceState = when (service) {
        ContentService.Tmdb -> tmdb
        ContentService.Mdblist -> mdblist
        ContentService.IntroDb -> introDb
        ContentService.TheIntroDb -> theIntroDb
    }

    fun with(state: ContentServiceState): ContentServicesState = when (state.service) {
        ContentService.Tmdb -> copy(tmdb = state)
        ContentService.Mdblist -> copy(mdblist = state)
        ContentService.IntroDb -> copy(introDb = state)
        ContentService.TheIntroDb -> copy(theIntroDb = state)
    }

    val anyConfigured: Boolean get() = tmdb.configured || mdblist.configured || introDb.configured || theIntroDb.configured
    val needsAttention: List<ContentServiceState>
        get() = listOf(tmdb, mdblist, introDb, theIntroDb).filter { it.status == CredentialStatus.NeedsAttention }
}

/** What the backend reports about an account-saved key. Never the key. */
data class AccountCredentialState(
    val service: ContentService,
    val configured: Boolean,
    val maskedKey: String? = null,
    val label: String? = null,
    val needsAttention: Boolean = false,
)

data class AccountCredentials(
    val tmdb: AccountCredentialState? = null,
    val mdblist: AccountCredentialState? = null,
    val introDb: AccountCredentialState? = null,
    val theIntroDb: AccountCredentialState? = null,
    val sharedFallbackAvailable: Boolean = true,
) {
    fun of(service: ContentService): AccountCredentialState? = when (service) {
        ContentService.Tmdb -> tmdb
        ContentService.Mdblist -> mdblist
        ContentService.IntroDb -> introDb
        ContentService.TheIntroDb -> theIntroDb
    }
}

enum class StorageChoice { SaveToStreamDek, ThisDeviceOnly }

enum class CredentialRemoval { Device, Account }

/**
 * A failure thrown where the screen can name it, rather than a sentence carried in an exception.
 *
 * The repository runs outside any composition, so the message it used to put into an
 * `IllegalStateException` was always English no matter what language the television was in. It
 * throws this instead and the screen resolves [CredentialFailure.messageRes] itself.
 */
class CredentialFailureException(val failure: CredentialFailure) : IllegalStateException(failure.name)

enum class CredentialFailure(@StringRes val messageRes: Int) {
    InvalidKey(R.string.credential_failure_invalid_key),
    ServiceUnavailable(R.string.credential_failure_service_unavailable),
    Malformed(R.string.credential_failure_malformed),
    NotSignedIn(R.string.credential_failure_not_signed_in);

    companion object {
        fun fromId(value: String?): CredentialFailure = when (value?.trim()?.lowercase()) {
            "invalid_key" -> InvalidKey
            "malformed" -> Malformed
            "service_unavailable" -> ServiceUnavailable
            else -> InvalidKey
        }
    }
}

internal fun maskServiceKey(apiKey: String): String = "•".repeat(8) + apiKey.trim().takeLast(4)

// ── Secure local storage ──────────────────────────────────────────────────────────────────────

/**
 * A device-only key at rest, encrypted under a key that never leaves the Android keystore.
 *
 * Same design as the phone's vault. A television is if anything the more exposed of the two —
 * they get sideloaded onto, sold on, and handed round households — so an API key sitting in a
 * readable preferences file is not an acceptable place to leave one.
 */
internal class SecureKeyVault(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(service: ContentService): String? {
        val stored = preferences.getString(storageKey(service), null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { decrypt(stored) }.getOrElse {
            // A keystore reset makes existing ciphertext permanently unreadable. Clearing it stops
            // every later read paying for the same failure; the viewer is simply asked again.
            clear(service)
            null
        }
    }

    fun write(service: ContentService, apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear(service)
            return true
        }
        return runCatching {
            preferences.edit().putString(storageKey(service), encrypt(trimmed)).apply()
        }.isSuccess
    }

    fun clear(service: ContentService) {
        preferences.edit().remove(storageKey(service)).apply()
    }

    fun has(service: ContentService): Boolean = read(service) != null

    private fun storageKey(service: ContentService) = "$KEY_PREFIX${service.id}"

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$FORMAT_VERSION:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(ciphertext)}"
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unrecognised stored credential format" }
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decoder.decode(parts[1])))
        return String(cipher.doFinal(decoder.decode(parts[2])), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Not authentication-bound: home rows load before anyone has touched the remote,
                // and a key that needed an unlock would simply never be readable there.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "streamdek_tv_service_credentials_v1"
        const val KEY_PREFIX = "credential_"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "streamdek_tv_service_credential_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val TAG_BITS = 128
    }
}

// ── The manager ───────────────────────────────────────────────────────────────────────────────

/**
 * The television's half of the credential architecture — the same contract the phone implements.
 *
 * Knows nothing about the network. It owns the local vault and the merge rule; the account's side
 * of the story arrives from whoever last read the bootstrap.
 */
class ServiceCredentialManager(context: Context) {
    private val vault = SecureKeyVault(context)
    private val preferences = context.applicationContext
        .getSharedPreferences("streamdek_tv_service_credential_meta_v1", Context.MODE_PRIVATE)

    fun deviceKey(service: ContentService): String? = vault.read(service)

    fun hasDeviceKey(service: ContentService): Boolean = vault.has(service)

    fun saveDeviceKey(service: ContentService, apiKey: String): Boolean {
        val saved = vault.write(service, apiKey)
        if (saved) {
            preferences.edit()
                .putString(maskKeyName(service), maskServiceKey(apiKey))
                .remove(rejectedKeyName(service))
                .apply()
        }
        return saved
    }

    fun clearDeviceKey(service: ContentService) {
        vault.clear(service)
        preferences.edit().remove(maskKeyName(service)).remove(rejectedKeyName(service)).apply()
    }

    fun deviceKeyMask(service: ContentService): String? =
        preferences.getString(maskKeyName(service), null)?.takeIf { it.isNotBlank() }

    fun markDeviceKeyRejected(service: ContentService) {
        preferences.edit().putBoolean(rejectedKeyName(service), true).apply()
    }

    fun deviceKeyRejected(service: ContentService): Boolean =
        preferences.getBoolean(rejectedKeyName(service), false)

    /** The key to attach to an outgoing request. A refused key is skipped, not replayed. */
    fun requestKey(service: ContentService): String? =
        if (deviceKeyRejected(service)) null else deviceKey(service)

    /**
     * Folds the account's answer together with this television's, into one state.
     *
     * The device key wins where both exist, matching the phone and the backend exactly. The
     * account copy is never hidden when that happens — the screen says it is there and offers to
     * fall back to it.
     */
    fun merge(service: ContentService, account: AccountCredentialState?): ContentServiceState {
        val deviceHeld = hasDeviceKey(service)
        val accountHeld = account?.configured == true
        return when {
            deviceHeld -> ContentServiceState(
                service = service,
                status = if (deviceKeyRejected(service)) CredentialStatus.NeedsAttention else CredentialStatus.Connected,
                storage = CredentialStorage.Device,
                maskedKey = deviceKeyMask(service),
                accountKeyAlsoAvailable = accountHeld,
            )
            accountHeld -> ContentServiceState(
                service = service,
                status = if (account!!.needsAttention) CredentialStatus.NeedsAttention else CredentialStatus.Connected,
                storage = CredentialStorage.Account,
                maskedKey = account.maskedKey,
                accountLabel = account.label,
            )
            else -> ContentServiceState(service = service)
        }
    }

    fun mergeAll(account: AccountCredentials?, previous: ContentServicesState): ContentServicesState =
        previous.copy(
            tmdb = merge(ContentService.Tmdb, account?.tmdb),
            mdblist = merge(ContentService.Mdblist, account?.mdblist),
            introDb = merge(ContentService.IntroDb, account?.introDb),
            theIntroDb = merge(ContentService.TheIntroDb, account?.theIntroDb),
            sharedFallbackAvailable = account?.sharedFallbackAvailable ?: previous.sharedFallbackAvailable,
            loaded = true,
        )

    /** Signing out leaves nothing of the previous viewer's keys on the television. */
    fun clearAll() {
        ContentService.all.forEach(::clearDeviceKey)
        preferences.edit().clear().apply()
    }

    private fun maskKeyName(service: ContentService) = "mask_${service.id}"
    private fun rejectedKeyName(service: ContentService) = "rejected_${service.id}"
}
