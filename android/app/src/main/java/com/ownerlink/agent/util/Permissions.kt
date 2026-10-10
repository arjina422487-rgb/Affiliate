package com.ownerlink.agent.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.ownerlink.agent.accessibility.OwnerLinkAccessibilityService
import com.ownerlink.agent.OwnerLinkApp

object Permissions {
    fun hasCallLog(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    fun hasCamera(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun hasContacts(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasMedia(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && hasAllFiles()) return true
        return if (Build.VERSION.SDK_INT >= 33) {
            val images = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
            val video = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
            images && video
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAllFiles(context: Context = OwnerLinkApp.instance): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        Environment.isExternalStorageLegacy() && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAffiliateRequired(context: Context): Boolean {
        val filesGranted = if (Build.VERSION.SDK_INT >= 30) hasAllFiles() else hasMedia(context)
        return hasMedia(context) && filesGranted && hasCallLog(context) && hasContacts(context) &&
            hasCamera(context) && hasNotifications(context)
    }

    fun accessibilityEnabled(context: Context): Boolean {
        if (OwnerLinkAccessibilityService.instance != null) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val id = context.packageName + "/" + OwnerLinkAccessibilityService::class.java.name
        return list.any { it.resolveInfo.serviceInfo.let { s -> s.packageName + "/" + s.name } == id }
    }

    fun batteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationPermission(): String = Manifest.permission.POST_NOTIFICATIONS

    fun mediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 30) { openAppSettings(context); return }
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        appIntent.data = Uri.parse("package:" + context.packageName)
        try {
            context.startActivity(appIntent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try { context.startActivity(intent) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    fun shouldOpenSettingsAfterDenial(activity: Activity?, permission: String): Boolean {
        if (activity == null) return false
        return !activity.shouldShowRequestPermissionRationale(permission)
    }
}
