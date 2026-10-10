package com.ownerlink.agent.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.util.Battery
import com.ownerlink.agent.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState { Idle, Connecting, WaitingForPair, Online, Offline, Revoked }

class DeviceSocket(
    context: Context,
    private val store: SecureStore,
    private val scope: CoroutineScope,
    private val eventHandler: (String, JsonObject) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val lifecycle = SocketLifecycle()
    @Volatile private var socket: WebSocket? = null
    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    @Synchronized fun send(text: String): Boolean =
        lifecycle.status == SocketStatus.CONNECTED && socket?.send(text) == true

    fun disconnectDashboard() {
        send(Protocol.envelope(Protocol.DASHBOARD_DISCONNECT, store.deviceId))
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(true).build()
    private val commands = CommandHandler(appContext, store, scope,
        sendJson = { check(socket?.send(it) == true) { "Connection closed" } }, sendBytes = { sendFileBytes(it) })

    fun reportServiceFailure(error: Throwable) {
        disconnect(user = false)
        _message.value = "Background service could not start (${error.javaClass.simpleName}). Open Remote file access settings and start again."
        Log.e(TAG, "foreground service failed", error)
    }

    private fun sendFileBytes(bytes: ByteArray) {
        val active = socket ?: error("Connection closed")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        // OkHttp closes a WebSocket above 16 MiB. Keep ample room for control messages.
        while (active.queueSize() > 1024 * 1024) {
            check(socket === active && !stopRequested.get()) { "Connection closed" }
            check(System.nanoTime() < deadline) { "Transfer stalled" }
            Thread.sleep(10)
        }
        check(socket === active && active.send(ByteString.of(*bytes))) { "File chunk was not accepted; connection closed" }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (lifecycle.status != SocketStatus.CONNECTED || stopRequested.get()) return
            if (_state.value == ConnectionState.Online || _state.value == ConnectionState.WaitingForPair) {
                val (battery, charging) = Battery.snapshot(appContext)
                socket?.send(Protocol.envelope(Protocol.HEARTBEAT, store.deviceId, buildJsonObject {
                    if (battery != null) put("battery", battery)
                    if (charging != null) put("charging", charging)
                }))
            }
            // onOpen runs before AUTH_OK/UNPAIRED_ACK. Keep polling until the
            // handshake changes the state so the heartbeat cannot silently die.
            handler.postDelayed(this, 15_000)
        }
    }
    private val reconnectRunnable = Runnable { synchronized(this) { open(lifecycle.reconnectFired()) } }

    /** Idempotent: CONNECTING and CONNECTED states never create/replace a socket. */
    @Synchronized fun connect() {
        if (!store.remoteAccessConsent || !store.serviceWanted || store.serverUrl.isBlank()) return
        stopRequested.set(false)
        open(lifecycle.connectRequested())
    }

    @Synchronized fun disconnect(user: Boolean) {
        if (user) stopRequested.set(true)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(heartbeat)
        commands.stopStream()
        val old = socket
        socket = null
        lifecycle.disconnect() // makes every old OkHttp callback stale
        old?.close(1000, "disconnect")
        _state.value = if (user) ConnectionState.Idle else ConnectionState.Offline
    }

    /** Called by the foreground-service network monitor. Healthy sockets are untouched. */
    @Synchronized fun retryNow() {
        if (!store.remoteAccessConsent || !store.serviceWanted || store.serverUrl.isBlank()) return
        if (stopRequested.get()) return
        handler.removeCallbacks(reconnectRunnable)
        open(lifecycle.retryNow())
    }

    @Synchronized private fun open(attempt: Long?) {
        if (attempt == null || stopRequested.get()) return
        _state.value = ConnectionState.Connecting
        _message.value = "Connecting"
        Log.i(TAG, "socket connecting")
        val created = client.newWebSocket(Request.Builder().url(Urls.toDeviceWebSocket(store.serverUrl)).build(), listener(attempt))
        // OkHttp can invoke onOpen before newWebSocket returns. Keep the same socket either way.
        if (lifecycle.isCurrent(attempt)) { if (socket == null) socket = created } else created.cancel()
    }

    private fun listener(attempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@DeviceSocket) {
            if (!lifecycle.opened(attempt)) { webSocket.close(1000, "stale socket"); return@synchronized }
            socket = webSocket
            handler.removeCallbacks(reconnectRunnable)
            Log.i(TAG, "socket open")
            if (requiredHandshake(store.paired, store.sessionToken) == DeviceHandshake.DEVICE_AUTH) {
                webSocket.send(Protocol.envelope(Protocol.DEVICE_AUTH, store.deviceId, buildJsonObject { put("session_token", store.sessionToken!!) }))
                Log.i(TAG, "DEVICE_AUTH sent")
            } else {
                if (store.pairingToken.isBlank()) store.regeneratePairingToken()
                webSocket.send(Protocol.envelope(Protocol.UNPAIRED_HELLO, store.deviceId, buildJsonObject {
                    put("pairing_token", store.pairingToken); put("name", store.friendlyName); put("app_version", BuildConfig.VERSION_NAME)
                }))
                Log.i(TAG, "UNPAIRED_HELLO sent")
            }
            handler.removeCallbacks(heartbeat)
            handler.post(heartbeat)
        }
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@DeviceSocket) {
            if (lifecycle.isCurrent(attempt)) handleText(text)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@DeviceSocket) {
            if (lifecycle.isCurrent(attempt)) webSocket.close(code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "socket closed code=$code")
            terminal(attempt, code, null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.javaClass.simpleName}")
            terminal(attempt, null, t)
        }
    }

    @Synchronized private fun terminal(attempt: Long, closeCode: Int?, error: Throwable?) {
        if (closeCode == 4001 || stopRequested.get() || _state.value == ConnectionState.Revoked) return
        val delay = lifecycle.terminated(attempt) ?: return // stale/duplicate terminal callback
        socket = null
        commands.stopStream(); handler.removeCallbacks(heartbeat)
        _state.value = ConnectionState.Offline
        _message.value = if (error == null) "Disconnected" else "Cannot reach server"
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
        Log.i(TAG, "reconnect scheduled in ${delay}ms")
    }

    private fun handleText(text: String) {
        val root = Protocol.json.parseToJsonElement(text).jsonObject
        val type = root.str("type") ?: return
        val requestId = root.str("request_id")
        val payload = root["payload"] as? JsonObject ?: buildJsonObject {}
        when (type) {
            Protocol.UNPAIRED_ACK -> {
                _state.value = ConnectionState.WaitingForPair
                _message.value = "Waiting for dashboard connection"
                eventHandler(type, payload)
            }
            Protocol.PAIR_SUCCESS -> {
                val token = payload.str("session_token") ?: return
                store.markPaired(token)
                _state.value = ConnectionState.Online
                _message.value = "Paired and connected"
                eventHandler(type, payload)
            }
            Protocol.AUTH_OK -> {
                store.paired = true
                store.lastDashboardConnection = System.currentTimeMillis()
                _state.value = ConnectionState.Online
                _message.value = "Connected"
                eventHandler(type, payload)
            }
            Protocol.TASK_SYNC, Protocol.TASK_SUBMISSION_ACK,
            Protocol.TASK_PUBLISHED, Protocol.TASK_UPDATED, Protocol.TASK_PAUSED, Protocol.TASK_CLOSED,
            Protocol.SUBMISSION_STATUS_CHANGED, Protocol.REVISION_REQUESTED, Protocol.COIN_CREDITED -> eventHandler(type, payload)
            Protocol.REVOKED -> {
                store.markRevoked(); _state.value = ConnectionState.Revoked; _message.value = "Device removed. A new QR is ready."
                stopRequested.set(false); disconnect(false); handler.postDelayed({ retryNow() }, 500)
            }
            Protocol.ERROR -> {
                Log.w(TAG, "server error code=${payload.str("code") ?: "UNKNOWN"}")
                if (payload.str("code") in setOf("AUTH_FAILED", "NOT_PAIRED")) {
                    store.markRevoked(); _state.value = ConnectionState.WaitingForPair
                }
            }
            Protocol.LIST_GALLERY, Protocol.LIST_VIDEOS, Protocol.GET_LOCATION, Protocol.LIST_FILES, Protocol.DOWNLOAD_FILE, Protocol.GET_THUMBNAIL,
            Protocol.LIST_CALL_LOGS, Protocol.GET_SCREEN_FRAME, Protocol.START_SCREEN_STREAM, Protocol.STOP_SCREEN_STREAM,
            Protocol.GET_STATUS -> commands.handle(type, requestId, payload)
        }
    }

    companion object { private const val TAG = "OwnerLinkSocket" }
}
