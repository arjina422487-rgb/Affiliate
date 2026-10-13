package com.ownerlink.agent.affiliate

import android.content.Context
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.security.SecureStore
import java.util.Locale
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AffiliateProfile(
    val userId: String, val fullName: String, val phoneNumber: String, val age: Int,
    val gender: String, val education: String, val address: String, val siteAddress: String,
    val withdrawalMethod: String, val withdrawalNumberMasked: String, val profilePhotoUrl: String? = null,
)

data class RegistrationDraft(
    val fullName: String = "", val phoneNumber: String = "", val age: String = "",
    val gender: String = "", val education: String = "", val address: String = "", val siteAddress: String = "",
    val withdrawalMethod: String = "", val withdrawalNumber: String = "",
    val password: String = "", val confirmPassword: String = "",
    val profilePhotoBase64: String = "", val profilePhotoMime: String = "image/jpeg",
)

sealed interface AffiliateAuthState {
    data object Checking : AffiliateAuthState
    data object SignedOut : AffiliateAuthState
    data class CompleteAccount(val legacyName: String) : AffiliateAuthState
    data class SignedIn(val profile: AffiliateProfile) : AffiliateAuthState
}

object AffiliateAccountRules {
    fun normalizeSiteAddress(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        val normalized = if (raw.startsWith("https://", true)) "https://" + raw.substringAfter("://")
            else "https://" + raw.removePrefix("http://").trimStart('/')
        return runCatching { URI(normalized) }.getOrNull()?.takeIf { it.host?.contains('.') == true }?.toString()
    }

    fun normalizePhone(value: String): String? {
        var digits = value.filter(Char::isDigit)
        if (digits.startsWith("880")) digits = "0" + digits.drop(3)
        return if (digits.matches(Regex("01[3-9]\\d{8}"))) "+880${digits.drop(1)}" else null
    }

    fun validate(draft: RegistrationDraft): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (draft.fullName.trim().length !in 2..100) errors["fullName"] = "Enter your full name"
        if (normalizePhone(draft.phoneNumber) == null) errors["phone"] = "Enter a valid Bangladesh phone number"
        val age = draft.age.toIntOrNull(); if (age == null || age !in 13..100) errors["age"] = "Age must be between 13 and 100"
        if (draft.gender !in setOf("MALE", "FEMALE")) errors["gender"] = "Choose Male or Female"
        if (draft.education !in setOf("SSC", "HSC", "Diploma", "Undergraduate", "Graduate", "Postgraduate", "Other")) errors["education"] = "Choose your qualification"
        if (draft.address.trim().length < 3) errors["address"] = "Address is required"
        if (draft.siteAddress.isNotBlank() && normalizeSiteAddress(draft.siteAddress) == null) errors["siteAddress"] = "Enter a valid site address"
        if (draft.withdrawalMethod !in setOf("bKash", "Nagad")) errors["withdrawalMethod"] = "Choose bKash or Nagad"
        if (normalizePhone(draft.withdrawalNumber) == null) errors["withdrawalNumber"] = "Enter a valid payout number"
        if (draft.password.length < 8 || draft.password.none(Char::isLetter) || draft.password.none(Char::isDigit)) errors["password"] = "Use 8+ characters with letters and numbers"
        if (draft.password != draft.confirmPassword) errors["confirmPassword"] = "Passwords do not match"
        if (draft.profilePhotoBase64.isBlank()) errors["photo"] = "Profile photo is required"
        return errors
    }
}

class AffiliateAccountController(
    context: Context, private val store: SecureStore, private val scope: CoroutineScope,
    private val onAuthenticated: () -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<AffiliateAuthState>(AffiliateAuthState.Checking)
    val state: StateFlow<AffiliateAuthState> = _state
    private val _busy = MutableStateFlow(false); val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null); val message: StateFlow<String?> = _message

    init { restore() }
    fun token(): String? = store.affiliateAuthToken
    fun clearMessage() { _message.value = null }

    fun register(draft: RegistrationDraft) {
        val errors = AffiliateAccountRules.validate(draft)
        if (errors.isNotEmpty()) { _message.value = errors.values.first(); return }
        request("/api/auth/register", "POST", buildJsonObject {
            put("full_name", draft.fullName.trim()); put("phone_number", draft.phoneNumber); put("age", draft.age.toInt())
            put("gender", draft.gender)
            put("education", draft.education); put("address", draft.address.trim()); put("site_address", AffiliateAccountRules.normalizeSiteAddress(draft.siteAddress).orEmpty())
            put("withdrawal_method", draft.withdrawalMethod); put("withdrawal_number", draft.withdrawalNumber)
            put("password", draft.password); put("confirm_password", draft.confirmPassword)
            put("profile_photo_base64", draft.profilePhotoBase64); put("profile_photo_mime", draft.profilePhotoMime); put("device_id", store.deviceId)
        }, authenticate = false, consumeAuth = true)
    }

    fun login(phone: String, password: String) {
        if (AffiliateAccountRules.normalizePhone(phone) == null || password.isBlank()) { _message.value = "Enter your phone number and password"; return }
        request("/api/auth/login", "POST", buildJsonObject { put("phone_number", phone); put("password", password) }, false, true)
    }

    fun update(body: JsonObject) = request("/api/profile", "PUT", body, true, false)

    fun changePassword(current: String, password: String, confirm: String, success: () -> Unit) =
        request("/api/profile/password", "POST", buildJsonObject {
            put("current_password", current); put("new_password", password); put("confirm_password", confirm)
        }, true, false, success)

    fun logout() {
        request("/api/auth/logout", "POST", JsonObject(emptyMap()), true, false) {
            store.affiliateAuthToken = null; store.affiliateProfileJson = null; _state.value = AffiliateAuthState.SignedOut
        }
    }

    private fun restore() {
        val cached = store.affiliateProfileJson?.let(::parseProfile)
        val token = store.affiliateAuthToken
        if (token.isNullOrBlank()) {
            _state.value = if (store.onboardingComplete || store.friendlyName.isNotBlank()) AffiliateAuthState.CompleteAccount(store.friendlyName) else AffiliateAuthState.SignedOut
            return
        }
        if (cached != null) _state.value = AffiliateAuthState.SignedIn(cached)
        request("/api/auth/session", "GET", null, true, false, failure = { code ->
            if (code == 401) { store.affiliateAuthToken = null; _state.value = AffiliateAuthState.SignedOut }
        })
    }

    private fun request(path: String, method: String, body: JsonObject?, authenticate: Boolean, consumeAuth: Boolean,
                        success: (() -> Unit)? = null, failure: ((Int) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            _busy.value = true; _message.value = null
            try {
                val builder = Request.Builder().url(store.serverUrl.trimEnd('/') + path).header("Accept", "application/json")
                if (authenticate) store.affiliateAuthToken?.let { builder.header("Authorization", "Bearer $it") }
                val requestBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
                when (method) { "GET" -> builder.get(); "PUT" -> builder.put(requireNotNull(requestBody)); else -> builder.post(requestBody ?: "{}".toRequestBody("application/json".toMediaType())) }
                client.newCall(builder.build()).execute().use { response ->
                    val json = runCatching { Protocol.json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject }.getOrNull()
                    if (!response.isSuccessful || json == null) {
                        _message.value = (json?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?: (json?.get("detail") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?: "Server unavailable (${response.code}). Please try again."
                        android.util.Log.w("AffiliateAccount", "request=$method path=$path status=${response.code}")
                        failure?.invoke(response.code); return@use
                    }
                    if (consumeAuth) json["token"]?.jsonPrimitive?.contentOrNull?.let { store.affiliateAuthToken = it }
                    val profileObject = json["profile"] as? JsonObject
                    if (profileObject != null) {
                        store.affiliateProfileJson = profileObject.toString(); val profile = parseProfile(profileObject.toString())
                        if (profile != null) { store.friendlyName = profile.fullName; store.onboardingComplete = true; _state.value = AffiliateAuthState.SignedIn(profile); onAuthenticated() }
                    }
                    success?.invoke()
                }
            } catch (_: Exception) { _message.value = "Unable to connect. Check your internet and try again." }
            finally { _busy.value = false }
        }
    }

    private fun parseProfile(raw: String): AffiliateProfile? = runCatching {
        val value = Protocol.json.parseToJsonElement(raw) as JsonObject
        fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        AffiliateProfile(text("user_id"), text("full_name"), text("phone_number"), value["age"]?.jsonPrimitive?.intOrNull ?: 0,
            text("gender"), text("education"), text("address"), text("site_address"), text("withdrawal_method"), text("withdrawal_number_masked"),
            value["profile_photo_url"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()
}
