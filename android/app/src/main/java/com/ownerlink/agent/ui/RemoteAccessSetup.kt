package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
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
import com.ownerlink.agent.service.ConnectionService
import com.ownerlink.agent.util.Permissions

@Composable
internal fun RemoteAccessSetup() {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    val connection by app.socket.state.collectAsState()
    val message by app.socket.message.collectAsState()
    var revision by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(revision, connection) { app.store.remoteAccessConsent && app.store.serviceWanted }
    var notice by remember { mutableStateOf("") }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.store.remoteCallLogsConsent = granted
        notice = if (granted) "Call log sharing enabled for your paired dashboard." else "Call log permission was not granted."
        revision++
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        app.store.remoteLocationConsent = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        notice = "Location permission updated. For requests while the app is closed, allow Location all the time in phone app settings."
        revision++
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notice = "Permissions updated. Android 11+: check All files access in Settings before browsing storage."
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.store.addSafTree(uri.toString())
            }.onSuccess { notice = "Selected folder is available to your paired dashboard." }
                .onFailure { notice = "Could not retain folder permission: ${it.javaClass.simpleName}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Remote access", style = MaterialTheme.typography.titleLarge)
        Text("Optional: connect only your own phone to a dashboard you control. The paired dashboard can browse and download permitted documents, pictures and videos while this app is in the background. Affiliate tasks do not require this access.")
        Text("Status: $connection${if (message.isBlank()) "" else " · $message"}")
        Text("Storage: ${if (Permissions.hasAllFiles()) "shared storage access enabled" else "select folders or grant storage access"}. Android protects other apps' private folders.")
        Text("Optional call logs: shares phone numbers, names, call times, types and durations when your paired dashboard requests them. Optional location: shares permitted coordinates, accuracy and timestamp on request. Neither is required for tasks or file access.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Share call logs", modifier = Modifier.weight(1f))
            Switch(checked = remember(revision) { app.store.remoteCallLogsConsent }, onCheckedChange = { selected ->
                if (selected) callPermission.launch(Manifest.permission.READ_CALL_LOG)
                else { app.store.remoteCallLogsConsent = false; revision++ }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Share location on request", modifier = Modifier.weight(1f))
            Switch(checked = remember(revision) { app.store.remoteLocationConsent }, onCheckedChange = { selected ->
                if (selected) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else { app.store.remoteLocationConsent = false; revision++ }
            })
        }
        Text("Location can be approximate or cached; check its timestamp. Android 10 uses the latest available location. For background requests, choose Location → Allow all the time in Phone app settings. You can disable either sharing switch at any time.")
        OutlinedButton(onClick = {
            val requested = mutableListOf<String>()
            if (Build.VERSION.SDK_INT < 30) requested += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            if (requested.isNotEmpty()) permission.launch(requested.toTypedArray())
            else Permissions.openAllFilesSettings(context)
        }) { Text("Allow storage / notifications") }
        if (Build.VERSION.SDK_INT >= 30) {
            OutlinedButton(onClick = { Permissions.openAllFilesSettings(context) }) { Text("Allow all shared files") }
        }
        OutlinedButton(onClick = { folder.launch(null) }) { Text("Choose a folder instead") }
        OutlinedButton(onClick = { Permissions.openBatterySettings(context) }) { Text("Battery background settings") }
        OutlinedButton(onClick = { Permissions.openAppSettings(context) }) { Text("Phone app settings / autostart") }
        Text("Xiaomi: allow Background autostart and set Battery saver to No restrictions. Infinix: allow autostart/background activity in the phone's app or power settings. Names vary by model. Keep network available; after Force stop, open the app again. After reboot, unlock the phone once.")
        if (enabled) {
            OutlinedButton(onClick = { ConnectionService.stop(context); revision++ }) { Text("Stop all remote access") }
        } else {
            Button(onClick = {
                if (app.store.serverUrl.isBlank()) {
                    notice = "Dashboard server is not configured. A new server URL is needed before building the connected app."
                } else if (!Permissions.hasNotifications(context)) {
                    notice = "Allow notifications first so remote access and its Stop control remain visible."
                    permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    app.store.remoteAccessConsent = true
                    app.store.serviceWanted = true
                    if (!ConnectionService.start(context)) notice = "Could not start background access. Check the connection message."
                    revision++
                }
            }) { Text("Enable access for my paired dashboard") }
        }
        key(revision) { if (notice.isNotBlank()) Text(notice) }
    }
}
