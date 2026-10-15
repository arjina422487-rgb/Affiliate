package com.ownerlink.agent.affiliate

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TaskField(
    val id: String,
    val type: String,
    val label: String,
    val required: Boolean,
    val helpText: String? = null,
    val choices: List<String> = emptyList(),
    val minLength: Int? = null,
    val minWords: Int? = null,
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val maxSizeMb: Int = 10,
    val allowedTypes: List<String> = emptyList(),
)

data class AffiliateTaskItem(
    val taskId: String,
    val categoryId: String,
    val title: String,
    val description: String,
    val instructions: String,
    val reward: String,
    val status: String,
    val priority: Int,
    val deadline: String?,
    val fields: List<TaskField>,
    val taskType: String = "FORM",
    val actionUrl: String? = null,
    val actionDurationSeconds: Int = 0,
)

data class AffiliateCategoryItem(val categoryId: String, val name: String)

data class AffiliateSubmissionItem(
    val submissionId: String,
    val clientSubmissionId: String,
    val taskId: String,
    val status: String,
    val fieldsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val reviewReason: String? = null,
)

data class AffiliateLedgerItem(
    val ledgerId: String,
    val taskId: String?,
    val amount: String,
    val reason: String,
    val balanceAfter: String,
    val createdAt: String,
)

private val BENGALI_TEXT = Regex("[\\u0980-\\u09FF]")

fun String.isBengaliText(): Boolean = BENGALI_TEXT.containsMatchIn(this)

/** User-facing task labels omit batch numbering while the task ID remains unique. */
fun displayTaskTitle(title: String): String = title.trim()
    .replace(Regex("""[\s._#-]+\d+(?:[._-]\d+)*$"""), "")
    .trim()

fun AffiliateTaskItem.platformName(): String? {
    val host = runCatching { URI(actionUrl.orEmpty()).host.orEmpty().lowercase().trim('.') }
        .getOrDefault("").removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
    val text = "$title $categoryId $description $instructions".lowercase()
    return when {
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            listOf("youtube", "youtu.be").any(text::contains) -> "YouTube"
        host == "instagram.com" || host.endsWith(".instagram.com") || host == "ig.me" ||
            "instagram" in text -> "Instagram"
        host == "tiktok.com" || host.endsWith(".tiktok.com") ||
            "tiktok" in text -> "TikTok"
        host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" || host.endsWith(".fb.watch") || host == "fb.me" ||
            listOf("facebook", "fb.watch").any(text::contains) -> "Facebook"
        else -> null
    }
}

fun AffiliateTaskItem.displayTitle(): String {
    val platform = platformName()
    if (taskType == "URL" && platform != null) return "$platform Video"
    val cleaned = displayTaskTitle(title)
    if (cleaned.isBengaliText()) return if (taskType == "URL") "Video Task" else "Task"
    return cleaned.ifBlank { if (taskType == "URL") "Video Task" else "Task" }
}

fun AffiliateTaskItem.displayDescription(): String = if (!description.isBengaliText()) description else
    if (taskType == "URL") "Open the assigned video or page and complete the task." else "Complete the task and submit your response in English."

fun AffiliateTaskItem.displayInstructions(): String = if (!instructions.isBengaliText()) instructions else
    if (taskType == "URL") "Open the link, watch the video for at least one minute, return to the app, tap “I Watched,” and submit."
    else "Complete the task and submit your response in English."

fun AffiliateTaskItem.displayCategory(fallback: String = "Task"): String {
    platformName()?.let { return it }
    return if (categoryId.isBengaliText()) fallback else categoryId.ifBlank { fallback }
}

fun TaskField.displayLabel(): String = when {
    !label.isBengaliText() -> label
    type == "note" -> "Task information"
    else -> "Your response"
}

fun TaskField.displayHelpText(): String? = helpText?.takeUnless { it.isBengaliText() }

fun TaskField.displayChoice(choice: String, index: Int): String =
    if (choice.isBengaliText()) "Option ${index + 1}" else choice

data class ProofValue(val name: String, val mime: String, val size: Long, val sha256: String, val localUri: String, val fileId: String = "")

object DynamicTaskValidator {
    private val prohibited = listOf("password", "passwd", "passcode", "2fa", "totp", "recovery_code", "cookie", "session_token", "private_key", "dashboard_token")

    fun validate(fields: List<TaskField>, values: Map<String, Any?>): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        values.keys.filter { key -> prohibited.any { key.lowercase().contains(it) } }.forEach { errors[it] = "Sensitive authentication data is not allowed" }
        fields.filter { it.type != "note" }.forEach { field ->
            val value = values[field.id]
            val empty = value == null || value == "" || value == emptyList<String>()
            if (field.required && empty) { errors[field.id] = "${field.label} is required"; return@forEach }
            if (empty) return@forEach
            when (field.type) {
                "short_text", "long_text" -> {
                    val text = value.toString().trim()
                    if (field.minLength != null && text.length < field.minLength) errors[field.id] = "Enter at least ${field.minLength} characters"
                    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (field.minWords != null && words.size < field.minWords) errors[field.id] = "Write at least ${field.minWords} words"
                    if (field.minWords != null && (text.length < 60 || words.map { it.lowercase() }.toSet().size < 5)) errors[field.id] = "Write a meaningful explanation (at least 60 characters and 5 different words)"
                    if (field.maxLength != null && text.length > field.maxLength) errors[field.id] = "Use no more than ${field.maxLength} characters"
                }
                "url" -> if (!validUrl(value.toString())) errors[field.id] = "Enter a valid public web address"
                "number" -> {
                    val number = value.toString().toDoubleOrNull()
                    if (number == null || (field.min != null && number < field.min) || (field.max != null && number > field.max)) errors[field.id] = "Enter a number in the allowed range"
                }
                "dropdown" -> if (value !in field.choices) errors[field.id] = "Choose an available option"
                "multi_select" -> if (value !is List<*> || value.any { it !in field.choices }) errors[field.id] = "Choose only available options"
                "checkbox" -> if (value !is Boolean || (field.required && value != true)) errors[field.id] = if (field.required) "This confirmation is required" else "Choose checked or unchecked"
                "datetime" -> if (runCatching { java.time.LocalDateTime.parse(value.toString()) }.isFailure && runCatching { java.time.Instant.parse(value.toString()) }.isFailure) errors[field.id] = "Enter a valid date and time"
                "image", "file" -> {
                    val proof = value as? ProofValue
                    if (proof == null || proof.size > field.maxSizeMb * 1024L * 1024L || !proof.sha256.matches(Regex("[0-9a-f]{64}")) || (field.allowedTypes.isNotEmpty() && proof.mime !in field.allowedTypes)) errors[field.id] = "Choose an allowed file under ${field.maxSizeMb} MB"
                }
            }
        }
        return errors
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

object AffiliateQueueRules {
    fun afterAttempt(sent: Boolean): String = if (sent) "SENDING" else "QUEUED"
    fun shouldRetry(status: String): Boolean = status in setOf("QUEUED", "SENDING")
}

data class ActivityCounts(val approved: Int, val waiting: Int, val rejected: Int, val revision: Int)

object AffiliatePresentationRules {
    fun activityCounts(items: List<AffiliateSubmissionItem>) = ActivityCounts(
        approved = items.count { it.status == "APPROVED" },
        waiting = items.count { it.status in setOf("QUEUED", "SENDING", "IN_PROGRESS", "PENDING_REVIEW") },
        rejected = items.count { it.status == "REJECTED" },
        revision = items.count { it.status == "REVISION_REQUESTED" },
    )

    fun validateCoinShare(recipientUrl: String, amountText: String, balanceText: String): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val uri = runCatching { URI(recipientUrl.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) errors["recipient"] = "Enter a valid https URL"
        val amount = amountText.toBigDecimalOrNull()
        val balance = balanceText.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        when {
            amount == null -> errors["amount"] = "Enter a numeric coin amount"
            amount < java.math.BigDecimal("1000") -> errors["amount"] = "Minimum send amount is 1000"
            amount > balance -> errors["amount"] = "Amount exceeds your current balance"
        }
        return errors
    }
}

object AffiliateJson {
    fun task(value: JsonObject): AffiliateTaskItem = AffiliateTaskItem(
        taskId = value.text("task_id"), categoryId = value.text("category_id"), title = value.text("title"),
        description = value.text("description"), instructions = value.text("instructions"), reward = value.text("reward", "0"),
        status = value.text("status"), priority = value["priority"]?.jsonPrimitive?.intOrNull ?: 0,
        deadline = value["end_at"]?.jsonPrimitive?.contentOrNull,
        fields = (value["field_schema"] as? JsonArray)?.mapNotNull { runCatching { field(it.jsonObject) }.getOrNull() }.orEmpty(),
        taskType = value.text("task_type", "FORM"),
        actionUrl = value["action_url"]?.jsonPrimitive?.contentOrNull,
        actionDurationSeconds = value["action_duration_seconds"]?.jsonPrimitive?.intOrNull ?: 0,
    )

    fun field(value: JsonObject) = TaskField(
        id = value.text("id"), type = value.text("type"), label = value.text("label"),
        required = value["required"]?.jsonPrimitive?.booleanOrNull ?: false,
        helpText = value["help_text"]?.jsonPrimitive?.contentOrNull,
        choices = (value["choices"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        minLength = value["min_length"]?.jsonPrimitive?.intOrNull, minWords = value["min_words"]?.jsonPrimitive?.intOrNull, maxLength = value["max_length"]?.jsonPrimitive?.intOrNull,
        min = value["min"]?.jsonPrimitive?.doubleOrNull, max = value["max"]?.jsonPrimitive?.doubleOrNull,
        maxSizeMb = value["max_size_mb"]?.jsonPrimitive?.intOrNull ?: 10,
        allowedTypes = (value["allowed_types"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
    )

    fun JsonObject.text(key: String, fallback: String = ""): String = this[key]?.jsonPrimitive?.contentOrNull ?: fallback
    fun objects(element: JsonElement?): List<JsonObject> = (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
