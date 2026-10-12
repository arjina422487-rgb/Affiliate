package com.ownerlink.agent.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationRepository(private val context: Context) {
    suspend fun latest(): JsonObject {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return error("LOCATION_PERMISSION_REQUIRED", "Location permission is required on this device.")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)
        val preferred = when {
            fine && providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        }
        val current = if (Build.VERSION.SDK_INT >= 30) preferred?.let { provider ->
            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }
            }
        } else null
        val location = current ?: last
            ?: return error("LOCATION_UNAVAILABLE", "No location is available yet. Turn on Location on the phone and try again.")
        return buildJsonObject {
            put("latitude", location.latitude); put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble()); put("timestamp", location.time)
            put("provider", location.provider ?: "device")
            put("cached", current == null)
        }
    }

    private fun error(code: String, message: String) = buildJsonObject {
        put("ok", false); put("code", code); put("message", message)
    }
}
