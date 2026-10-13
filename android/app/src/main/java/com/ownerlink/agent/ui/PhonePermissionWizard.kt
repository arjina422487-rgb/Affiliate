package com.ownerlink.agent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.util.Permissions

/** Explicit, skippable sharing permissions; account creation is independent. */
@Composable
internal fun PhonePermissionWizard(close: () -> Unit) {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && step == 1 && Permissions.hasAllFiles(context)) step = 2
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (step == 2) app.store.remoteCallLogsConsent = granted[Manifest.permission.READ_CALL_LOG] == true
        if (step == 3) app.store.remoteLocationConsent = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        step++
    }
    LaunchedEffect(step) {
        when (step) {
            2 -> request.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
            3 -> request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            4 -> if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(context)) {
                request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else step++
        }
    }
    val title = when(step) { 0 -> "Phone sharing"; 1 -> "Files & gallery"; 2 -> "Call logs"; 3 -> "Location"; 4 -> "Notifications"; else -> "All set" }
    val detail = when(step) {
        0 -> "Allow access to share files, photos, videos, call logs and location with your dashboard. Android will ask one permission at a time. You can decline any permission."
        1 -> "Allow your connected dashboard to browse and download shared files, photos and videos."
        2 -> "Allow call numbers, call times and durations to be read by your connected dashboard."
        3 -> "Allow your connected dashboard to request your phone's location."
        4 -> "Show connection status and a Stop control in notifications."
        else -> "Manage or stop sharing from Redeem code. Your account can be created now."
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(detail)
            if (step in 1..4) LinearProgressIndicator(progress = { step / 4f }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(onClick = {
            when(step) {
                0 -> step = 1
                1 -> if (Permissions.hasAllFiles(context)) step++ else if (Build.VERSION.SDK_INT >= 30) Permissions.openAllFilesSettings(context) else request.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                2 -> request.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
                3 -> request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                4 -> if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(context)) request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else step++
                else -> close()
            }
        }, enabled = step !in 2..4) { Text(if (step > 4) "Done" else if (step == 1 && Permissions.hasAllFiles(context)) "Continue" else "Allow access") }
    }, dismissButton = {
        TextButton(onClick = { if (step in 1..4) step++ else close() }) { Text(if (step in 1..4) "Skip" else "Not now") }
    })
}
