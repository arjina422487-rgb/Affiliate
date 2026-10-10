package com.ownerlink.agent.network

import android.content.Context
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.files.FileRef
import com.ownerlink.agent.files.FileRepository
import com.ownerlink.agent.gallery.GalleryRepository
import com.ownerlink.agent.calllog.CallLogRepository
import com.ownerlink.agent.location.LocationRepository
import com.ownerlink.agent.remote.ScreenStreamer
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.util.Battery
import com.ownerlink.agent.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class CommandHandler(
    private val context: Context,
    private val store: SecureStore,
    private val scope: CoroutineScope,
    private val sendJson: (String) -> Unit,
    private val sendBytes: (ByteArray) -> Unit,
) {
    private val gallery = GalleryRepository(context)
    private val callLogs = CallLogRepository(context)
    private val location = LocationRepository(context)
    private val files = FileRepository(context, store)
    private val streamer = ScreenStreamer(scope, store.deviceId, sendJson, sendBytes)
    private val transferGeneration = AtomicLong(0)

    fun handle(type: String, requestId: String?, payload: JsonObject) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!store.remoteAccessConsent || !store.serviceWanted) {
                    err(requestId, "ACCESS_DISABLED", "Remote file access is disabled on this phone")
                    return@launch
                }
                if (type !in setOf(Protocol.LIST_FILES, Protocol.DOWNLOAD_FILE, Protocol.GET_STATUS,
                        Protocol.LIST_GALLERY, Protocol.LIST_VIDEOS, Protocol.GET_THUMBNAIL)) {
                    err(requestId, "UNSUPPORTED", "Only remote file access is enabled")
                    return@launch
                }
                when (type) {
                    Protocol.LIST_GALLERY -> {
                        val data = gallery.list(payload.str("cursor"), payload.int("limit") ?: 200, payload.str("album"))
                        if (data["ok"]?.toString() == "false") errorPayload(requestId, data) else ok(requestId, data)
                    }
                    Protocol.LIST_VIDEOS -> {
                        val data = gallery.listVideos(payload.str("cursor"), payload.int("limit") ?: 40)
                        if (data["ok"]?.toString() == "false") errorPayload(requestId, data) else ok(requestId, data)
                    }
                    Protocol.GET_LOCATION -> {
                        val data = location.latest()
                        if (data["ok"]?.toString() == "false") errorPayload(requestId, data) else ok(requestId, data)
                    }
                    Protocol.LIST_FILES -> {
                        val data = files.list(payload.str("folder_id"))
                        if (data["ok"]?.toString() == "false") errorPayload(requestId, data) else ok(requestId, data)
                    }
                    Protocol.LIST_CALL_LOGS -> {
                        val data = callLogs.list(payload.int("cursor") ?: 0, payload.int("limit") ?: 50, payload.str("filter"))
                        if (data["ok"]?.toString() == "false") errorPayload(requestId, data) else ok(requestId, data)
                    }
                    Protocol.GET_THUMBNAIL -> sendThumb(requestId, payload.str("file_id"))
                    Protocol.DOWNLOAD_FILE -> sendFile(requestId, payload)
                    Protocol.GET_STATUS -> ok(requestId, statusPayload())
                    Protocol.GET_SCREEN_FRAME -> {
                        streamer.sendOne(payload.int("quality") ?: 55, payload.int("max_width") ?: 720)
                        ok(requestId, buildJsonObject { put("accepted", true) })
                    }
                    Protocol.START_SCREEN_STREAM -> {
                        val fps = payload.int("fps") ?: 1
                        val quality = payload.int("quality") ?: 55
                        val maxWidth = payload.int("max_width") ?: 720
                        streamer.start(fps, quality, maxWidth)
                        ok(requestId, buildJsonObject { put("streaming", true); put("capture", "accessibility") })
                    }
                    Protocol.STOP_SCREEN_STREAM -> {
                        streamer.stop()
                        ok(requestId, buildJsonObject { put("streaming", false) })
                    }
                    else -> err(requestId, "UNKNOWN", "Unsupported command")
                }
            } catch (e: SecurityException) {
                err(requestId, "INVALID_PATH", e.message ?: "Not allowed")
            } catch (e: Exception) {
                val code = when {
                    e.message?.contains("permission", true) == true -> "PERMISSION_MISSING"
                    e.message?.contains("not found", true) == true -> "FILE_NOT_FOUND"
                    else -> "ERROR"
                }
                err(requestId, code, e.message ?: "Request failed")
            }
        }
    }

    fun stopStream() {
        transferGeneration.incrementAndGet()
        streamer.stop()
    }

    private fun sendThumb(requestId: String?, fileId: String?) {
        if (requestId == null || fileId == null) return
        val ref = FileIds.decode(fileId)
        val jpeg = when (ref) {
            is FileRef.Gallery -> gallery.thumbnailJpeg(ref.mediaId)
            else -> error("thumbnail only for gallery items")
        }
        val tid = UUID.fromString(requestId)
        sendBytes(BinaryFrames.frame(BinaryFrames.KIND_THUMB, tid, 0, jpeg, last = true))
        ok(requestId, buildJsonObject { put("mime", "image/jpeg") })
    }

    private fun sendFile(requestId: String?, payload: JsonObject) {
        val generation = transferGeneration.get()
        val transferRaw = payload.str("transfer_id") ?: requestId ?: return
        val fileId = payload.str("file_id") ?: error("file not found")
        val ref = FileIds.decode(fileId)
        val transferId = UUID.fromString(transferRaw)
        val offset = payload.str("offset")?.toLongOrNull() ?: 0L
        val requestedLength = payload.str("length")?.toLongOrNull()
        require(offset >= 0 && (requestedLength == null || requestedLength > 0)) { "Invalid file range" }
        val (name, mime, size, stream) = when (ref) {
            is FileRef.Gallery -> {
                val (n, m) = gallery.displayName(ref.mediaId)
                Quad(n, m, gallery.sizeOf(ref.mediaId), gallery.openOriginal(ref.mediaId))
            }
            else -> {
                val (ins, n, sz) = files.open(ref)
                Quad(n, files.mimeOf(n), sz, ins)
            }
        }
        if (offset > 0 && (size == null || offset >= size)) {
            stream.close()
            err(transferRaw, "INVALID_RANGE", "Requested range is outside this file")
            return
        }
        val transferSize = size?.let { minOf(it - offset, requestedLength ?: Long.MAX_VALUE) }
        stream.use { input ->
            sendJson(
                Protocol.envelope(
                    Protocol.FILE_BEGIN,
                    store.deviceId,
                    buildJsonObject {
                        put("transfer_id", transferRaw)
                        put("name", name)
                        if (mime != null) put("mime", mime)
                        if (transferSize != null) put("size", transferSize)
                        if (size != null) put("total_size", size)
                        put("offset", offset)
                    },
                    transferRaw,
                ),
            )
            var skipRemaining = offset
            while (skipRemaining > 0) {
                val skipped = input.skip(skipRemaining)
                if (skipped > 0) skipRemaining -= skipped
                else { check(input.read() != -1) { "File ended while seeking" }; skipRemaining-- }
            }
            val buf = ByteArray(64 * 1024)
            var seq = 0
            var remaining = transferSize ?: requestedLength ?: Long.MAX_VALUE
            while (remaining > 0) {
                check(store.remoteAccessConsent && store.serviceWanted) { "Remote file access stopped" }
                check(transferGeneration.get() == generation) { "Connection changed during file transfer" }
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                sendBytes(BinaryFrames.frame(BinaryFrames.KIND_FILE, transferId, seq, chunk, last = false))
                seq++
                remaining -= n
            }
            check(transferGeneration.get() == generation) { "Connection changed during file transfer" }
        }
        sendJson(
            Protocol.envelope(
                Protocol.FILE_END,
                store.deviceId,
                buildJsonObject { put("transfer_id", transferRaw) },
                transferRaw,
            ),
        )
        ok(requestId, buildJsonObject { put("done", true) })
    }

    private fun statusPayload(): JsonObject {
        val (battery, charging) = Battery.snapshot(context)
        return buildJsonObject {
            put("name", store.friendlyName)
            put("app_version", BuildConfig.VERSION_NAME)
            put("paired", store.paired)
            put("accessibility", Permissions.accessibilityEnabled(context))
            put("screenshot", Permissions.accessibilityEnabled(context))
            put("manage_storage", Permissions.hasAllFiles())
            put("media", Permissions.hasMedia(context))
            put("call_log", callLogs.hasPermission())
            put("location", androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            put("camera_mic", BuildConfig.FEATURE_CAMERA_MIC)
            put("camera_mic_available", false)
            if (battery != null) put("battery", battery)
            if (charging != null) put("charging", charging)
        }
    }

    private fun ok(requestId: String?, data: JsonObject) {
        sendJson(
            Protocol.envelope(
                Protocol.RESPONSE,
                store.deviceId,
                buildJsonObject {
                    put("ok", true)
                    put("data", data)
                },
                requestId,
            ),
        )
    }

    private fun err(requestId: String?, code: String, message: String) {
        sendJson(
            Protocol.envelope(
                Protocol.ERROR,
                store.deviceId,
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
                requestId,
            ),
        )
    }

    private fun errorPayload(requestId: String?, data: JsonObject) {
        err(requestId, data.str("code") ?: "ERROR", data.str("message") ?: "Device error")
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
