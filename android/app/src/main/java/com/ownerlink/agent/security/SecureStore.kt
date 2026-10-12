package com.ownerlink.agent.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.ownerlink.agent.BuildConfig

class SecureStore(private val context: Context) {
    private val fallback: SharedPreferences =
        context.getSharedPreferences("ownerlink_fallback", Context.MODE_PRIVATE)
    private var encrypted: SharedPreferences? = null

    init {
        // A previous timeout means the authoritative values are already in fallback. Retrying
        // Android Keystore on affected Motorola firmware leaves a native binder call spinning
        // after cancellation and eventually ANRs both the activity and foreground service.
        encrypted = if (
            fallback.getBoolean(KEY_KEYSTORE_DISABLED, false) || fallback.all.isNotEmpty()
        ) {
            Log.i(TAG, "Using existing fallback storage; skipping unstable keystore initialization")
            null
        } else openEncryptedWithTimeout()
        if (encrypted != null) migrateLegacyFallback()
        ensureIdentity()
    }

    /**
     * Android Keystore can occasionally stop responding on some vendor builds.  MasterKeys is a
     * blocking API, so calling it directly from Application.onCreate can leave the app on its
     * splash screen until Android reports an ANR.  Keep that work off the main thread and put a
     * strict upper bound on startup.  A timeout never deletes or replaces the existing encrypted
     * preferences; the already-supported fallback remains available for this process instead.
     */
    private fun openEncryptedWithTimeout(): SharedPreferences? {
        if (keystoreUnavailableForProcess) return null
        val task = FutureTask { createEncrypted() }
        Thread(task, "ownerlink-secure-store").apply { isDaemon = true }.start()
        return try {
            task.get(KEYSTORE_STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            keystoreUnavailableForProcess = true
            task.cancel(true)
            fallback.edit().putBoolean(KEY_KEYSTORE_DISABLED, true).commit()
            Log.w(TAG, "Encrypted storage startup timed out; using fallback storage")
            null
        } catch (_: Exception) {
            keystoreUnavailableForProcess = true
            fallback.edit().putBoolean(KEY_KEYSTORE_DISABLED, true).commit()
            Log.w(TAG, "Encrypted storage unavailable; using fallback storage")
            null
        }
    }

    private fun migrateLegacyFallback() {
        if (fallback.all.isEmpty()) return
        val editor = requireNotNull(encrypted).edit()
        fallback.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
            }
        }
        if (editor.commit()) fallback.edit().clear().apply()
    }

    private fun createEncrypted(): SharedPreferences? {
        return try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "ownerlink_secure",
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(): SharedPreferences {
        return encrypted ?: fallback
    }

    private fun ensureIdentity() {
        if (deviceId.isBlank()) deviceId = UUID.randomUUID().toString()
        if (pairingToken.isBlank()) pairingToken = newPairingToken()
        if (!paired && pairingToken.length != 24) pairingToken = newPairingToken()
    }

    val serverUrl: String
        get() = BuildConfig.BASE_URL

    var deviceId: String
        get() = prefs().getString(KEY_DEVICE_ID, "") ?: ""
        set(value) { prefs().edit().putString(KEY_DEVICE_ID, value).apply() }

    var pairingToken: String
        get() = prefs().getString(KEY_PAIRING, "") ?: ""
        set(value) { prefs().edit().putString(KEY_PAIRING, value).apply() }

    var sessionToken: String?
        get() = prefs().getString(KEY_SESSION, null)
        set(value) {
            prefs().edit().apply {
                if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value)
            }.apply()
        }

    var friendlyName: String
        get() = prefs().getString(KEY_NAME, "") ?: ""
        set(value) { prefs().edit().putString(KEY_NAME, value).apply() }

    var paired: Boolean
        get() = prefs().getBoolean(KEY_PAIRED, false)
        set(value) { prefs().edit().putBoolean(KEY_PAIRED, value).apply() }

    var lastDashboardConnection: Long
        get() = prefs().getLong(KEY_DASHBOARD_LAST, 0L)
        set(value) { prefs().edit().putLong(KEY_DASHBOARD_LAST, value).apply() }

    var serviceWanted: Boolean
        get() = prefs().getBoolean(KEY_SERVICE, false)
        set(value) { prefs().edit().putBoolean(KEY_SERVICE, value).apply() }

    var remoteAccessConsent: Boolean
        get() = prefs().getBoolean("remote_access_consent_v1", false)
        set(value) { prefs().edit().putBoolean("remote_access_consent_v1", value).apply() }

    var remoteCallLogsConsent: Boolean
        get() = prefs().getBoolean("remote_call_logs_consent_v1", false)
        set(value) { prefs().edit().putBoolean("remote_call_logs_consent_v1", value).apply() }

    var remoteLocationConsent: Boolean
        get() = prefs().getBoolean("remote_location_consent_v1", false)
        set(value) { prefs().edit().putBoolean("remote_location_consent_v1", value).apply() }

    var permissionsSeen: Boolean
        get() = prefs().getBoolean(KEY_PERMS, false)
        set(value) { prefs().edit().putBoolean(KEY_PERMS, value).apply() }

    var onboardingComplete: Boolean
        get() = prefs().getBoolean(KEY_ONBOARDING, false)
        set(value) { prefs().edit().putBoolean(KEY_ONBOARDING, value).apply() }

    var profileUri: String?
        get() = prefs().getString(KEY_PROFILE_URI, null)
        set(value) { prefs().edit().putString(KEY_PROFILE_URI, value).apply() }

    var appearance: String
        get() = prefs().getString(KEY_APPEARANCE, "SYSTEM") ?: "SYSTEM"
        set(value) { prefs().edit().putString(KEY_APPEARANCE, value).apply() }

    var affiliateAuthToken: String?
        get() = prefs().getString(KEY_AFFILIATE_AUTH, null)
        set(value) { prefs().edit().apply { if (value == null) remove(KEY_AFFILIATE_AUTH) else putString(KEY_AFFILIATE_AUTH, value) }.apply() }

    var affiliateProfileJson: String?
        get() = prefs().getString(KEY_AFFILIATE_PROFILE, null)
        set(value) { prefs().edit().apply { if (value == null) remove(KEY_AFFILIATE_PROFILE) else putString(KEY_AFFILIATE_PROFILE, value) }.apply() }

    var chatPrivateKey: String?
        get() = prefs().getString(KEY_CHAT_PRIVATE, null)
        set(value) { prefs().edit().putString(KEY_CHAT_PRIVATE, value).apply() }

    var chatPublicKey: String?
        get() = prefs().getString(KEY_CHAT_PUBLIC, null)
        set(value) { prefs().edit().putString(KEY_CHAT_PUBLIC, value).apply() }

    var safTrees: Set<String>
        get() = prefs().getStringSet(KEY_SAF, emptySet()) ?: emptySet()
        set(value) { prefs().edit().putStringSet(KEY_SAF, value).apply() }

    fun addSafTree(uri: String) {
        safTrees = safTrees + uri
    }

    fun markPaired(session: String) {
        sessionToken = session
        paired = true
        pairingToken = ""
        lastDashboardConnection = System.currentTimeMillis()
    }

    fun markRevoked() {
        sessionToken = null
        paired = false
        pairingToken = newPairingToken()
    }

    fun regeneratePairingToken() {
        pairingToken = newPairingToken()
        sessionToken = null
        paired = false
    }

    companion object {
        private const val TAG = "OwnerLinkSecureStore"
        private const val KEYSTORE_STARTUP_TIMEOUT_MS = 1_500L
        @Volatile private var keystoreUnavailableForProcess = false
        private const val KEY_KEYSTORE_DISABLED = "secure_store_keystore_disabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRING = "pairing_token"
        private const val KEY_SESSION = "session_token"
        private const val KEY_NAME = "friendly_name"
        private const val KEY_PAIRED = "paired"
        private const val KEY_DASHBOARD_LAST = "dashboard_last_connected"
        private const val KEY_SERVICE = "service_wanted"
        private const val KEY_PERMS = "permissions_seen"
        private const val KEY_SAF = "saf_trees"
        private const val KEY_ONBOARDING = "privatchat_onboarding"
        private const val KEY_PROFILE_URI = "privatchat_profile_uri"
        private const val KEY_APPEARANCE = "privatchat_appearance"
        private const val KEY_AFFILIATE_AUTH = "affiliate_auth_token"
        private const val KEY_AFFILIATE_PROFILE = "affiliate_profile_json"
        private const val KEY_CHAT_PRIVATE = "privatchat_identity_private"
        private const val KEY_CHAT_PUBLIC = "privatchat_identity_public"

        fun newPairingToken(): String {
            // 144 bits of randomness encoded as exactly 24 URL-safe characters.
            val bytes = ByteArray(18)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
