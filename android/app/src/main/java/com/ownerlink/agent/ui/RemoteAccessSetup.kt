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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
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
        notice = "Location updated. Background access needs Allow all the time in phone settings."
        revision++
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notice = "Permissions updated."
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sharing permissions", style = MaterialTheme.typography.titleLarge)
        Text("Choose what your connected dashboard can access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (enabled) "Enabled · $connection" else "Not enabled", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        PermissionPanel("Files, photos & videos", "Browse and download shared files from your paired dashboard.") {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) Permissions.openAllFilesSettings(context)
                else permission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(if (Permissions.hasAllFiles(context)) "Manage file access" else "Allow files")
            }
            TextButton(onClick = { folder.launch(null) }) { Text("Choose specific folders") }
        }
        PermissionPanel("Call logs", "Share call numbers, times and durations on request.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Share call logs", Modifier.weight(1f))
                Switch(checked = remember(revision) { app.store.remoteCallLogsConsent }, onCheckedChange = { selected ->
                    if (selected) callPermission.launch(Manifest.permission.READ_CALL_LOG)
                    else { app.store.remoteCallLogsConsent = false; revision++ }
                })
            }
        }
        PermissionPanel("Location", "Share your permitted location on dashboard request.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Share location", Modifier.weight(1f))
                Switch(checked = remember(revision) { app.store.remoteLocationConsent }, onCheckedChange = { selected ->
                    if (selected) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else { app.store.remoteLocationConsent = false; revision++ }
                })
            }
        }
        if (enabled) {
            OutlinedButton(onClick = { ConnectionService.stop(context); revision++ }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Stop phone sharing") }
        } else {
            Button(onClick = {
                if (app.store.serverUrl.isBlank()) notice = "Server is not configured."
                else if (!Permissions.hasNotifications(context)) {
                    notice = "Allow notifications to show the access status and Stop control."
                    permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    app.store.remoteAccessConsent = true
                    app.store.serviceWanted = true
                    if (!ConnectionService.start(context)) notice = "Could not connect. Please try again."
                    revision++
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) { Text("Enable phone sharing") }
        }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionPanel(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(20).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
