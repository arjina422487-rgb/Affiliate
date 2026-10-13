package com.ownerlink.agent.affiliate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.str

data class AffiliateUiState(
    val categories: List<AffiliateCategoryItem> = emptyList(),
    val tasks: List<AffiliateTaskItem> = emptyList(),
    val submissions: List<AffiliateSubmissionItem> = emptyList(),
    val ledger: List<AffiliateLedgerItem> = emptyList(),
    val balance: String = "0",
    val dashboardRewardClaimed: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
)

class AffiliateController(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val authToken: () -> String?,
    private val profileJson: () -> String?,
    private val sendServer: (String) -> Boolean,
) {
    private val syncBusy = AtomicBoolean(false)
    private val taskHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private val appContext = context.applicationContext
    private val store = AffiliateStore(context)
    val localData = UserDataFile(context)
    private val notifications = AffiliateNotifications(context)
    private val _state = MutableStateFlow(AffiliateUiState())
    val state: StateFlow<AffiliateUiState> = _state

    init {
        refreshLocal()
        // WebSocket publish events remain the fast path. This low-frequency full
        // refresh recovers automatically if an event was missed during sleep or
        // a Render reconnect without creating another socket.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                val process = android.app.ActivityManager.RunningAppProcessInfo()
                android.app.ActivityManager.getMyMemoryState(process)
                if (process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    requestSync(forceFull = true)
                }
            }
        }
    }

    fun handle(type: String, payload: JsonObject) {
        when (type) {
            Protocol.AUTH_OK, Protocol.UNPAIRED_ACK, Protocol.PAIR_SUCCESS, Protocol.CHAT_IDENTITY_ACK -> requestSync(forceFull = true)
            Protocol.TASK_SYNC -> applySync(payload)
            Protocol.TASK_SUBMISSION_ACK -> {
                (payload["submission"] as? JsonObject)?.let { saveServerSubmission(it) }
                // A successful submission can unlock the next server-held sequence item. Request
                // a full snapshot so its older creation revision does not hide it behind cursor.
                requestSync(forceFull = true)
            }
            Protocol.SUBMISSION_STATUS_CHANGED, Protocol.REVISION_REQUESTED, Protocol.COIN_CREDITED -> {
                (payload["submission"] as? JsonObject)?.let { saveServerSubmission(it) }
                val title = (payload["submission"] as? JsonObject)?.str("task_title") ?: "Task update"
                notifications.show(type, title)
                requestSync()
            }
            Protocol.TASK_PUBLISHED, Protocol.TASK_UPDATED, Protocol.TASK_PAUSED, Protocol.TASK_CLOSED -> {
                notifications.show(type, "Task list updated")
                // A full snapshot removes stale cached tasks after pause/archive/delete and
                // also reveals newly published items regardless of their original revision.
                requestSync(forceFull = true)
            }
        }
    }

    fun requestSync(forceFull: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            retryPending()
            syncNow(forceFull)
        }
    }

    private fun workerHttp(path: String, body: JsonObject? = null): JsonObject? {
        val token = authToken()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val builder = Request.Builder().url(BuildConfig.BASE_URL.trimEnd('/') + path)
                .header("Authorization", "Bearer $token").header("Accept", "application/json")
            if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            taskHttp.newCall(builder.build()).execute().use { response ->
                val data = runCatching { Protocol.json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject }.getOrNull()
                if (!response.isSuccessful || data == null) {
                    val error = if (response.code == 404) "Task service needs an update. Please try again later."
                        else data?.str("error") ?: "Could not load tasks. Please try again."
                    _state.value = _state.value.copy(loading = false, message = error)
                    Log.w(TAG, "task HTTP status=${response.code}")
                    null
                } else { _state.value = _state.value.copy(message = null); data }
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(loading = false, message = "Could not connect. Please try again.")
            null
        }
    }

    private fun syncNow(forceFull: Boolean): Boolean {
        if (authToken().isNullOrBlank() || !syncBusy.compareAndSet(false, true)) return false
        try {
            val cursor = if (forceFull) 0 else store.dao.cursor()?.toIntOrNull() ?: 0
            val sent = sendServer(Protocol.envelope(Protocol.TASK_SYNC_REQUEST, deviceId, buildJsonObject {
                put("cursor", cursor); authToken()?.let { put("auth_token", it) }
            }))
            if (sent) return true
            val data = workerHttp("/api/worker/sync?device_id=$deviceId&cursor=0") ?: return false
            applySync(data)
            return true
        } finally { syncBusy.set(false) }
    }

    fun submit(task: AffiliateTaskItem, values: JsonObject) {
        scope.launch(Dispatchers.IO) {
            val localId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = AffiliateSubmissionEntity(localId, localId, task.taskId, task.title, task.reward, "QUEUED", values.toString(), null, now, now)
            store.dao.saveSubmission(entity)
            rebuildRecord()
            send(entity)
            refreshLocalNow()
            AffiliateSyncWorker.enqueue(appContext)
        }
    }

    fun retryAndSync() {
        scope.launch(Dispatchers.IO) { retryPending(); requestSync() }
    }

    fun backgroundAttempt(): Boolean {
        retryPending()
        return syncNow(false)
    }

    private fun retryPending() { store.dao.pending().forEach(::send) }

    @Synchronized private fun send(entity: AffiliateSubmissionEntity) {
        val fields = runCatching { Protocol.json.parseToJsonElement(entity.fieldsJson) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        val payload = buildJsonObject {
            put("task_id", entity.taskId); put("client_submission_id", entity.clientSubmissionId)
            put("device_id", deviceId); put("fields", fields); authToken()?.let { put("auth_token", it) }
        }
        val sent = sendServer(Protocol.envelope(Protocol.TASK_SUBMIT, deviceId, payload))
        if (sent) store.dao.saveSubmission(entity.copy(status = "SENDING", updatedAt = System.currentTimeMillis()))
        else {
            val response = workerHttp("/api/worker/submissions", payload)
            val submission = response?.get("submission") as? JsonObject
            if (submission != null) {
                saveServerSubmissionNow(submission); rebuildRecord(); refreshLocalNow()
                syncNow(true)
            } else store.dao.saveSubmission(entity.copy(status = "QUEUED", updatedAt = System.currentTimeMillis()))
        }
    }

    private fun applySync(payload: JsonObject) {
        scope.launch(Dispatchers.IO) {
            val categories = AffiliateJson.objects(payload["categories"]).map { AffiliateCategoryItem(it.str("category_id").orEmpty(), it.str("name").orEmpty()) }.filter { it.categoryId.isNotBlank() }
            val taskObjects = AffiliateJson.objects(payload["tasks"])
            Log.i(TAG, "task sync received tasks=${taskObjects.size} full=${payload["full"]?.jsonPrimitive?.contentOrNull}")
            val entities = taskObjects.map { value ->
                val task = AffiliateJson.task(value)
                AffiliateTaskEntity(task.taskId, task.categoryId, task.title, task.description, task.instructions, task.reward,
                    task.status, task.priority, task.deadline, (value["field_schema"] ?: JsonArray(emptyList())).toString(),
                    value["revision"]?.jsonPrimitive?.intOrNull ?: 0, task.taskType, task.actionUrl, task.actionDurationSeconds)
            }
            if (payload["full"]?.jsonPrimitive?.contentOrNull == "true") store.dao.clearTasks()
            store.dao.saveTasks(entities)
            AffiliateJson.objects(payload["submissions"]).forEach(::saveServerSubmissionNow)
            val wallet = payload["wallet"] as? JsonObject
            store.dao.clearLedger()
            val ledger = AffiliateJson.objects(wallet?.get("ledger")).map { value -> AffiliateLedgerEntity(
                value.str("ledger_id") ?: return@map AffiliateLedgerEntity(UUID.randomUUID().toString(), null, "0", "Invalid", "0", ""),
                value.str("task_id"), value.str("amount") ?: "0", value.str("reason") ?: "", value.str("balance_after") ?: "0", value.str("created_at") ?: "",
            ) }
            store.dao.saveLedger(ledger)
            store.dao.saveSync(AffiliateSyncEntity(value = payload["cursor"]?.jsonPrimitive?.contentOrNull ?: "0"))
            val dashboardRewardClaimed = wallet?.get("dashboard_reward_claimed")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            rebuildRecord(); refreshLocalNow(wallet?.str("balance"), categories, dashboardRewardClaimed)
        }
    }

    private fun saveServerSubmission(value: JsonObject) { scope.launch(Dispatchers.IO) { saveServerSubmissionNow(value); rebuildRecord(); refreshLocalNow() } }

    private fun saveServerSubmissionNow(value: JsonObject) {
        val clientId = value.str("client_submission_id") ?: return
        val previous = store.dao.byClientId(clientId)
        val taskId = value.str("task_id") ?: previous?.taskId ?: return
        val task = store.dao.task(taskId)
        val now = System.currentTimeMillis()
        store.dao.saveSubmission(AffiliateSubmissionEntity(
            submissionId = value.str("submission_id") ?: previous?.submissionId ?: clientId,
            clientSubmissionId = clientId, taskId = taskId, taskTitle = value.str("task_title") ?: previous?.taskTitle ?: task?.title ?: taskId,
            reward = value.str("reward") ?: previous?.reward ?: task?.reward ?: "0", status = value.str("status") ?: "PENDING_REVIEW",
            fieldsJson = (value["submitted_fields"] ?: previous?.fieldsJson?.let { Protocol.json.parseToJsonElement(it) } ?: JsonObject(emptyMap())).toString(),
            reviewReason = value.str("review_reason"), createdAt = previous?.createdAt ?: now, updatedAt = now,
        ))
    }

    private fun refreshLocal() { scope.launch(Dispatchers.IO) { refreshLocalNow() } }

    private fun refreshLocalNow(balanceOverride: String? = null, categoryOverride: List<AffiliateCategoryItem>? = null,
                                dashboardRewardOverride: Boolean? = null) {
        val taskEntities = store.dao.tasks()
        val submissions = store.dao.submissions()
        val ledger = store.dao.ledger()
        val tasks = taskEntities.map { entity -> AffiliateJson.task(buildJsonObject {
            put("task_id", entity.taskId); put("category_id", entity.categoryId); put("title", entity.title); put("description", entity.description)
            put("instructions", entity.instructions); put("reward", entity.reward); put("status", entity.status); put("priority", entity.priority)
            put("task_type", entity.taskType); entity.actionUrl?.let { put("action_url", it) }; put("action_duration_seconds", entity.actionDurationSeconds)
            if (entity.deadline != null) put("end_at", entity.deadline); put("field_schema", Protocol.json.parseToJsonElement(entity.schemaJson))
        }) }
        val uiSubmissions = submissions.map { AffiliateSubmissionItem(it.submissionId, it.clientSubmissionId, it.taskId, it.status, it.fieldsJson, it.createdAt, it.updatedAt, it.reviewReason) }
        val uiLedger = ledger.map { AffiliateLedgerItem(it.ledgerId, it.taskId, it.amount, it.reason, it.balanceAfter, it.createdAt) }
        val balance = balanceOverride ?: ledger.firstOrNull()?.balanceAfter ?: _state.value.balance
        val dashboardRewardClaimed = dashboardRewardOverride
            ?: ledger.any { it.reason.equals("Dashboard connection bonus", ignoreCase = true) }
            || _state.value.dashboardRewardClaimed
        _state.value = AffiliateUiState(categoryOverride ?: _state.value.categories, tasks, uiSubmissions, uiLedger,
            balance, dashboardRewardClaimed, loading = false)
    }

    private fun rebuildRecord() = localData.rebuild(store.dao.tasks(), store.dao.submissions(), store.dao.ledger(), profileJson())

    private companion object { const val TAG = "AffiliateSync" }

}
