package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.affiliate.AffiliateSubmissionItem
import com.ownerlink.agent.affiliate.AffiliateAuthState
import com.ownerlink.agent.affiliate.AffiliateProfile
import com.ownerlink.agent.affiliate.AffiliateTaskItem
import com.ownerlink.agent.affiliate.DynamicTaskValidator
import com.ownerlink.agent.affiliate.AffiliatePresentationRules
import com.ownerlink.agent.affiliate.ProofValue
import com.ownerlink.agent.affiliate.TaskField
import com.ownerlink.agent.network.ConnectionState
import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.ui.theme.ThemeMode
import com.ownerlink.agent.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private enum class AffiliateTab { Tasks, Activity, Wallet, Settings }

@Composable
fun AffiliateHome() {
    val app = OwnerLinkApp.instance
    LaunchedEffect(Unit) { app.affiliate.requestSync(forceFull = true) }
    var tab by rememberSaveable { mutableStateOf(AffiliateTab.Tasks) }
    var openTask by remember { mutableStateOf<AffiliateTaskItem?>(null) }
    GlassBackdrop {
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        if (openTask == null) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        NavigationBar(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().glassPanel(30), containerColor = Color.Transparent, tonalElevation = 0.dp, windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
            AffiliateTab.entries.forEach { item ->
                val icon = when (item) { AffiliateTab.Tasks -> Icons.AutoMirrored.Rounded.Assignment; AffiliateTab.Activity -> Icons.Rounded.History; AffiliateTab.Wallet -> Icons.Rounded.AccountBalanceWallet; AffiliateTab.Settings -> Icons.Rounded.Settings }
                val scale by animateFloatAsState(if (tab == item) 1.12f else .94f, spring(), label = "nav-scale")
                val lift by animateFloatAsState(if (tab == item) -3f else 0f, spring(), label = "nav-lift")
                NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, item.name, Modifier.scale(scale).graphicsLayer { translationY = lift }) }, label = { Text(item.name, style = MaterialTheme.typography.labelMedium) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .13f)))
            }
        } }
    }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { AdaptivePane {
        Crossfade(targetState = openTask to tab, animationSpec = tween(220), label = "affiliate-navigation") { (detail, destination) -> when {
            detail != null -> AffiliateTaskDetail(detail) { openTask = null }
            destination == AffiliateTab.Tasks -> AffiliateTasks(open = { openTask = it }, connectDashboard = { tab = AffiliateTab.Settings })
            destination == AffiliateTab.Activity -> AffiliateActivity()
            destination == AffiliateTab.Wallet -> AffiliateWallet()
            else -> AffiliateSettings()
        } }
    } } }
    }
}

@Composable
private fun AffiliateHeader(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).glassPanel(16), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Stars, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.size(12.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun GradientHeroCard(title: String, value: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .4f), RoundedCornerShape(32.dp)), shape = RoundedCornerShape(32.dp), elevation = CardDefaults.cardElevation(8.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF4547BE), Color(0xFF7561E9), Color(0xFF319CA8)))).padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = .82f))
            AnimatedContent(value, label = "hero-value") { Text(it, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
            Text(subtitle, color = Color.White.copy(alpha = .76f))
            action?.invoke()
        }
    }
}

@Composable
private fun PremiumCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.glassPanel(), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun AffiliateTasks(open: (AffiliateTaskItem) -> Unit, connectDashboard: () -> Unit) {
    val app = OwnerLinkApp.instance
    val state by app.affiliate.state.collectAsState()
    val socket by app.socket.state.collectAsState()
    var category by remember { mutableStateOf<String?>(null) }
    val submitted = state.submissions.associateBy { it.taskId }
    val rawAvailable = state.tasks.filter { it.status == "PUBLISHED" && submitted[it.taskId] == null }
    val available = rawAvailable.filter { category == null || it.categoryId == category }
    val accountState by app.affiliateAccount.state.collectAsState()
    val userName = (accountState as? AffiliateAuthState.SignedIn)?.profile?.fullName ?: app.store.friendlyName.ifBlank { "Affiliate User" }
    val paired = app.store.paired
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AffiliateHeader("Welcome back, $userName", if (socket in setOf(ConnectionState.Online, ConnectionState.WaitingForPair)) "Your next opportunity is ready" else "Offline mode · cached tasks available") }
        item { GradientHeroCard("Your next opportunity", available.firstOrNull()?.title ?: "Stay ready", available.firstOrNull()?.let { "Earn ${it.reward} coin" } ?: "New tasks will appear automatically") }
        if (!paired && !state.dashboardRewardClaimed) item { ConnectDashboardTask(connectDashboard) }
        if (state.categories.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item { AssistChip(onClick = { category = null }, label = { Text("All") }, leadingIcon = if (category == null) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) }; items(state.categories) { item -> AssistChip(onClick = { category = item.categoryId }, label = { Text(item.name) }, leadingIcon = if (category == item.categoryId) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } } }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!state.loading && state.tasks.isEmpty()) item { AffiliateEmpty("No tasks available", "Published tasks will appear here. Cached tasks remain visible offline.") }
        if (available.isNotEmpty()) { item { SectionLabel("Available tasks") }; items(available, key = { "available-${it.taskId}" }) { TaskCard(it, null, open) } }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ConnectDashboardTask(open: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "dashboard-task-pulse")
    val glow by pulse.animateFloat(0.12f, 0.28f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dashboard-task-glow")
    Card(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFF52D6A2).copy(alpha = .42f), RoundedCornerShape(28.dp)).clickable(onClick = open),
        shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(5.dp),
    ) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF52D6A2).copy(alpha = glow), MaterialTheme.colorScheme.surface))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("ONE-TIME REWARD", style = MaterialTheme.typography.labelSmall, color = Color(0xFF169B70), fontWeight = FontWeight.Bold); Text("Redeem This Code", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold) }
            }
            Button(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Connect & Earn", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun SectionLabel(value: String) { Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)); Spacer(Modifier.width(9.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(task: AffiliateTaskItem, submission: AffiliateSubmissionItem?, open: (AffiliateTaskItem) -> Unit) {
    val categories = OwnerLinkApp.instance.affiliate.state.collectAsState().value.categories
    val categoryName = categories.firstOrNull { it.categoryId == task.categoryId }?.name ?: task.categoryId
    val enabled = submission?.status !in setOf("PENDING_REVIEW", "APPROVED")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(if (pressed) .975f else 1f, spring(stiffness = 520f, dampingRatio = .72f), label = "task-card-press")
    val accent = if (task.taskType == "URL") Color(0xFF7657FF) else MaterialTheme.colorScheme.primary
    val stateText = (submission?.status ?: task.status).replace('_', ' ')
    Card(
        Modifier.fillMaxWidth().scale(cardScale).animateContentSize().glassPanel()
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { open(task) },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).scale(if (pressed) .94f else 1f).background(Brush.linearGradient(listOf(accent, Color(0xFF389DA9))), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(if (task.taskType == "URL") Icons.Rounded.PlayCircle else Icons.Rounded.Description, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(13.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(if (task.taskType == "URL") "WATCH & EARN" else "ANSWER & EARN", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold); Text(categoryName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(Modifier.background(Color(0xFFFFB84D).copy(alpha = .14f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Stars, null, tint = Color(0xFFFFA21A), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(task.reward, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface) }; Text("COINS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold); Text(task.description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.background(statusColor(submission?.status ?: task.status).copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 7.dp)) { AnimatedContent(stateText, label = "task-state") { Text(it, color = statusColor(submission?.status ?: task.status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) } }
                Button(onClick = { open(task) }, enabled = enabled, shape = RoundedCornerShape(16.dp)) { Icon(if (task.taskType == "URL") Icons.Rounded.PlayCircle else Icons.Rounded.Description, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(if (task.taskType == "URL") "Let's Watch" else "Open Form", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun AffiliateTaskDetail(task: AffiliateTaskItem, back: () -> Unit) {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val values = remember(task.taskId) { mutableStateMapOf<String, Any?>() }
    val errors = remember(task.taskId) { mutableStateMapOf<String, String>() }
    var review by remember { mutableStateOf(false) }
    var linkOpened by rememberSaveable(task.taskId) { mutableStateOf(false) }
    var watchedConfirmed by rememberSaveable(task.taskId) { mutableStateOf(false) }
    val actionComplete = task.taskType != "URL" || watchedConfirmed
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Column { Text(task.categoryId.replaceFirstChar(Char::uppercase), color = MaterialTheme.colorScheme.primary); Text(task.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Reward"); Text("${task.reward} coin", fontWeight = FontWeight.Bold) } }
        TaskStepCard("1", "Understand the task", task.description, Icons.Rounded.Description)
        TaskStepCard("2", "Follow the instructions", task.instructions, Icons.AutoMirrored.Rounded.Assignment)
        if (task.taskType == "URL") Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Text("3", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }; Spacer(Modifier.width(10.dp)); Text("Watch and confirm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                Text("Open the assigned video or web page. When you finish, return here and confirm that you watched it.")
                WatchActionButton(linkOpened) {
                    task.actionUrl?.let { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onSuccess { linkOpened = true }.onFailure { Toast.makeText(context, "Unable to open this link", Toast.LENGTH_SHORT).show() } }
                }
                AnimatedVisibility(linkOpened) {
                    OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); watchedConfirmed = true }, enabled = !watchedConfirmed, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.CloudDone, null); Spacer(Modifier.width(8.dp)); Text(if (watchedConfirmed) "Watched ✓" else "I Watched")
                    }
                }
                AnimatedContent(watchedConfirmed, label = "task-watch-state") { confirmed -> Text(if (confirmed) "Watch confirmed — you can now complete the task ✓" else if (linkOpened) "Tap I Watched after finishing the video" else "Tap Let’s Watch to open the link", color = if (confirmed) Color(0xFF2E9E68) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (task.fields.isNotEmpty()) { HorizontalDivider(); Text(if (task.taskType == "URL") "Share your video review" else "3 · Your response", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); if (task.fields.any { it.minWords != null }) Text("You may write in Bengali, Hindi, or English. Explain your answers in full sentences.", color = MaterialTheme.colorScheme.onSurfaceVariant); task.fields.forEach { field -> DynamicField(field, values[field.id], { values[field.id] = it; errors.remove(field.id) }, errors[field.id]) } }
        Button(onClick = { errors.clear(); errors.putAll(DynamicTaskValidator.validate(task.fields, values)); if (errors.isEmpty() && actionComplete) review = true }, enabled = actionComplete, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (task.taskType == "URL" && !actionComplete) "Confirm I Watched first" else "Review and complete") }
        Text("Only the answers and proof files you explicitly choose are submitted. Other phone files are never scanned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Review before submitting") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(task.title, fontWeight = FontWeight.Bold); task.fields.filter { it.type != "note" && values[it.id] != null }.forEach { Text("${it.label}: ${displayValue(values[it.id])}") }; Text("After submission, this task will be Pending Review.") } }, confirmButton = { Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.affiliate.submit(task, valuesToJson(values)); review = false; back() }) { Text("Submit task") } }, dismissButton = { TextButton(onClick = { review = false }) { Text("Go back") } })
}

@Composable
private fun WatchActionButton(opened: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "watch-pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "watch-button-scale",
    )
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp).scale(scale)) {
        Icon(if (opened) Icons.Rounded.OpenInNew else Icons.Rounded.PlayCircle, null)
        Spacer(Modifier.width(9.dp))
        Text(if (opened) "Watch Again" else "Let's Watch", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TaskStepCard(number: String, title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PremiumCard(Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.Top) { Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("$number · $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
internal fun DynamicField(field: TaskField, value: Any?, update: (Any?) -> Unit, error: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readProof(context, uri, field.maxSizeMb) }
                .onSuccess(update)
                .onFailure { Toast.makeText(context, it.message ?: "Unable to read proof", Toast.LENGTH_SHORT).show() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (field.type) {
            "note" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(field.label, Modifier.padding(14.dp)) }
            "long_text" -> OutlinedTextField(value?.toString() ?: "", update, Modifier.fillMaxWidth(), label = { Text(label(field)) }, minLines = 4, supportingText = { Column { field.helpText?.let { Text(it) }; field.minWords?.let { minimum -> val text = value?.toString()?.trim().orEmpty(); val words = text.split(Regex("\\s+")).count { it.isNotBlank() }; Text("$words / $minimum words · ${text.length} / 60 characters minimum") } } })
            "short_text", "url", "number", "datetime" -> OutlinedTextField(value?.toString() ?: "", update, Modifier.fillMaxWidth(), label = { Text(label(field)) }, supportingText = { field.helpText?.let { Text(it) } })
            "dropdown" -> { Text(label(field)); Column { field.choices.forEach { choice -> AssistChip(onClick = { update(choice) }, label = { Text(choice) }, leadingIcon = if (value == choice) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } } }
            "multi_select" -> { val selected = (value as? List<*>)?.filterIsInstance<String>().orEmpty(); Text(label(field)); Column { field.choices.forEach { choice -> AssistChip(onClick = { update(if (choice in selected) selected - choice else selected + choice) }, label = { Text(choice) }, leadingIcon = if (choice in selected) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } } }
            "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value == true, { update(it) }); Text(label(field)) }
            "image", "file" -> { Text(label(field)); OutlinedButton(onClick = { picker.launch(if (field.type == "image") arrayOf("image/*") else field.allowedTypes.ifEmpty { listOf("*/*") }.toTypedArray()) }) { Text(if (value is ProofValue) value.name else "Choose ${if (field.type == "image") "image" else "document"}") }; if (value is ProofValue) Text("${value.mime} · ${formatSize(value.size)}", style = MaterialTheme.typography.bodySmall) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun AffiliateActivity() {
    val state by OwnerLinkApp.instance.affiliate.state.collectAsState()
    val counts = AffiliatePresentationRules.activityCounts(state.submissions)
    val waiting = state.submissions.filter { it.status in setOf("QUEUED", "SENDING", "IN_PROGRESS", "PENDING_REVIEW", "REVISION_REQUESTED") }
    val history = state.submissions.filterNot { it in waiting }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AffiliateHeader("Activity", "Submission and review timeline") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatCard("Approved", counts.approved, Color(0xFF45C486))
                AnimatedStatCard("Waiting", counts.waiting, Color(0xFFFFB84D))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedStatCard("Rejected", counts.rejected, MaterialTheme.colorScheme.error)
                AnimatedStatCard("Needs revision", counts.revision, Color(0xFF8D7CFF))
            }
        }
        if (waiting.isNotEmpty()) { item { SectionLabel("Pending & review") }; items(waiting, key = { "waiting-${it.clientSubmissionId}" }) { item -> ActivitySubmissionCard(item, state.tasks.firstOrNull { it.taskId == item.taskId }?.title) } }
        item { SectionLabel("Recent activity") }
        if (state.submissions.isEmpty()) item { AffiliateEmpty("No activity yet", "Your queued, submitted, reviewed, and rewarded work will appear here.") }
        items(history, key = { "history-${it.clientSubmissionId}" }) { item -> ActivitySubmissionCard(item, state.tasks.firstOrNull { it.taskId == item.taskId }?.title) }
    }
}

@Composable private fun ActivitySubmissionCard(item: AffiliateSubmissionItem, taskTitle: String?) {
    PremiumCard(Modifier.fillMaxWidth().animateContentSize()) { Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(statusColor(item.status).copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null, tint = statusColor(item.status)) }
        Spacer(Modifier.size(12.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(taskTitle ?: item.taskId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusChip(item.status)
            item.reviewReason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(DateFormat.getDateTimeInstance().format(Date(item.updatedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } }
}

@Composable private fun RowScope.AnimatedStatCard(label: String, target: Int, color: Color) {
    val value by animateIntAsState(target, tween(500), label = "$label-count")
    PremiumCard(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape)); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    } }
}

@Composable private fun StatusChip(status: String) {
    Text(status.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase), color = statusColor(status), style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun AffiliateWallet() {
    val state by OwnerLinkApp.instance.affiliate.state.collectAsState()
    var shareCoin by remember { mutableStateOf(false) }
    val balanceTarget = state.balance.toFloatOrNull() ?: 0f
    val balance by animateFloatAsState(balanceTarget, tween(650), label = "coin-balance")
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AffiliateHeader("Wallet", "Rewards with a transparent ledger") }
        item { GradientHeroCard("Coin Balance", "${formatCoin(balance)} coin", "Verified by the Affiliate server") { Button(onClick = { shareCoin = true }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.size(8.dp)); Text("Share Coin") } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { WalletStat("Earned this week", earnedSince(state.ledger, 7)); WalletStat("Earned this month", earnedSince(state.ledger, 30)) } }
        item { SectionLabel("Earnings & ledger") }
        if (state.ledger.isEmpty()) item { AffiliateEmpty("No transactions", "Approved task rewards and audited adjustments will appear here.") }
        items(state.ledger, key = { it.ledgerId }) { item -> PremiumCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(item.reason, style = MaterialTheme.typography.titleMedium); Text(item.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("${if (item.amount.startsWith("-")) "" else "+"}${item.amount}", fontWeight = FontWeight.Bold, color = if (item.amount.startsWith("-")) MaterialTheme.colorScheme.error else Color(0xFF299B68)) } } }
    }
    if (shareCoin) ShareCoinSheet(state.balance) { shareCoin = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ShareCoinSheet(balance: String, close: () -> Unit) {
    var recipient by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }; var unavailable by remember { mutableStateOf(false) }
    val errors = if (attempted) AffiliatePresentationRules.validateCoinShare(recipient, amount, balance) else emptyMap()
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = close) { Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Share Coin", style = MaterialTheme.typography.headlineMedium); Text("Send your coin after receive verification is available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Minimum send amount: 1000", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(recipient, { recipient = it; unavailable = false }, Modifier.fillMaxWidth(), label = { Text("Recipient URL") }, placeholder = { Text("https://...") }, isError = errors["recipient"] != null, supportingText = { errors["recipient"]?.let { Text(it) } })
        OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' }; unavailable = false }, Modifier.fillMaxWidth(), label = { Text("Coin Amount") }, isError = errors["amount"] != null, supportingText = { errors["amount"]?.let { Text(it) } })
        AnimatedVisibility(unavailable) { Text("Coin sharing will be activated after recipient verification is configured.", color = MaterialTheme.colorScheme.primary) }
        Button(onClick = { attempted = true; if (AffiliatePresentationRules.validateCoinShare(recipient, amount, balance).isEmpty()) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); unavailable = true } }, Modifier.fillMaxWidth()) { Text("Continue") }
        Text("Your balance is never changed until the server confirms a transfer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
internal fun AffiliateSettings() {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appearance by app.appearance.collectAsState()
    var showDashboard by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var dashboardGeneration by remember { mutableStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Toast.makeText(context, "For background location, choose Allow all the time in Affiliate app permissions.", Toast.LENGTH_LONG).show()
        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    val accountState by app.affiliateAccount.state.collectAsState()
    val accountProfile = (accountState as? AffiliateAuthState.SignedIn)?.profile
    val avatar by produceState<android.graphics.Bitmap?>(null, app.store.profileUri) {
        value = withContext(Dispatchers.IO) { runCatching { app.store.profileUri?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) } }.getOrNull() }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use(app.affiliate.localData::exportTo) }.onSuccess { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Toast.makeText(context, "Affiliate data exported", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, "Unable to export data", Toast.LENGTH_SHORT).show() }
    }
    if (showProfile && accountProfile != null) { AffiliateProfileScreen(accountProfile) { showProfile = false }; return }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 22.dp))
        Text("Welcome, ${accountProfile?.fullName ?: app.store.friendlyName.ifBlank { "Affiliate User" }}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        SettingsTitle("Account"); PremiumCard(Modifier.fillMaxWidth().clickable { showProfile = true }) { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.border(2.dp, Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)), CircleShape).padding(5.dp)) {
                if (avatar != null) Image(avatar!!.asImageBitmap(), "Profile avatar", Modifier.size(58.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop) else Box(Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text((accountProfile?.fullName ?: "A").take(1).uppercase(), style = MaterialTheme.typography.headlineMedium) }
            }
            Spacer(Modifier.size(14.dp)); Column(Modifier.weight(1f)) { Text(accountProfile?.fullName ?: "Affiliate user", style = MaterialTheme.typography.titleLarge); Text("View or edit account", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        SettingsTitle("Text Downloader"); SettingsRow(Icons.Rounded.Download, "Download My Data", "Export your Affiliate activity as a TXT file") { if (app.affiliate.localData.file.isFile) exporter.launch("Affiliate-user-data.txt") else Toast.makeText(context, "No activity to export yet", Toast.LENGTH_SHORT).show() }
        SettingsTitle("Appearance"); PremiumCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ThemeMode.entries.forEach { mode -> val text = if (mode == ThemeMode.SYSTEM) "System Default" else mode.name.lowercase().replaceFirstChar(Char::uppercase); if (appearance == mode) Button(onClick = { app.setAppearance(mode) }, modifier = Modifier.weight(1f)) { Text(text) } else OutlinedButton(onClick = { app.setAppearance(mode) }, modifier = Modifier.weight(1f)) { Text(text) } } } }
        SettingsTitle("Background Access")
        SettingsRow(Icons.Rounded.LocationOn, "Location Access", "Allow location, then select Allow all the time") {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
        SettingsRow(Icons.Rounded.BatterySaver, "Keep Affiliate Running", "Remove battery restrictions for reliable background connection") {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        RemoteAccessSetup()
        SettingsTitle("Dashboard pairing"); SettingsRow(Icons.Rounded.QrCode2, "Pair my dashboard", if (app.store.paired) "Dashboard paired" else "Code grants dashboard access to permitted phone files") { showDashboard = !showDashboard }
        if (showDashboard) {
            if (app.store.paired) { PremiumCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Dashboard connected", style = MaterialTheme.typography.titleMedium); Text("Regenerate only when you want to replace the current dashboard connection.", color = MaterialTheme.colorScheme.onSurfaceVariant); OutlinedButton(onClick = { app.store.regeneratePairingToken(); app.socket.disconnect(false); app.socket.retryNow(); dashboardGeneration++ }) { Text("Regenerate Dashboard Code") } } } }
            else { val code = remember(app.store.pairingToken, dashboardGeneration) { app.store.pairingToken }; PremiumCard(Modifier.fillMaxWidth()) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Dashboard Pairing Code", style = MaterialTheme.typography.titleMedium); Text(code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)); haptic.performHapticFeedback(HapticFeedbackType.LongPress); Toast.makeText(context, "Dashboard pairing code copied", Toast.LENGTH_SHORT).show() }) { Text("Copy Dashboard Pairing Code") } } } }
        }
        SettingsTitle("App Information"); PremiumCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Affiliate", style = MaterialTheme.typography.titleLarge); Text("Software Version  ${BuildConfig.VERSION_NAME}"); Text("Build Number  ${BuildConfig.VERSION_CODE}") } }
        SettingsTitle("Logout"); SettingsRow(Icons.Rounded.Logout, "Logout", "Sign out securely") { app.affiliateAccount.logout() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AffiliateProfileScreen(profile: AffiliateProfile, back: () -> Unit) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current; val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        val encoded = withContext(Dispatchers.IO) { compressProfilePhoto(context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)) }
        encoded?.let { app.affiliateAccount.update(buildJsonObject { put("profile_photo_base64", it); put("profile_photo_mime", "image/jpeg") }) }
    } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } }
        item { PremiumCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Box(Modifier.size(82.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, Modifier.size(42.dp)) }; Text(profile.fullName, style = MaterialTheme.typography.headlineSmall); Text(profile.phoneNumber); OutlinedButton(onClick = { photoPicker.launch(arrayOf("image/*")) }) { Text("Change Profile Photo") } } } }
        item { PremiumCard(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ProfileLine("Account Number", profile.userId); ProfileLine("Age", profile.age.toString()); ProfileLine("Gender", profile.gender.lowercase().replaceFirstChar { it.titlecase() }.ifBlank { "Not set" }); ProfileLine("Education", profile.education); ProfileLine("Address", profile.address); ProfileLine("Site Address", profile.siteAddress); ProfileLine("Withdrawal Method", profile.withdrawalMethod); ProfileLine("Withdrawal Number", profile.withdrawalNumberMasked); Button(onClick = { edit = true }, Modifier.fillMaxWidth()) { Text("Edit Profile") }; OutlinedButton(onClick = { password = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("Change Password") } } } }
    }
    if (edit) ProfileEditDialog(profile, { edit = false }) { body -> app.affiliateAccount.update(body); edit = false }
    if (password) ChangePasswordDialog({ password = false }) { current, next, confirm ->
        app.affiliateAccount.changePassword(current, next, confirm) { password = false; Toast.makeText(context, "Password changed", Toast.LENGTH_SHORT).show() }
    }
}

@Composable private fun ChangePasswordDialog(close: () -> Unit, save: (String, String, String) -> Unit) {
    var current by remember { mutableStateOf("") }; var next by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    val valid = current.isNotBlank() && next.length >= 8 && next.any(Char::isLetter) && next.any(Char::isDigit) && next == confirm
    AlertDialog(onDismissRequest = close, title = { Text("Change Password") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(current, { current = it }, label = { Text("Current Password") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(next, { next = it }, label = { Text("New Password") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm New Password") }, visualTransformation = PasswordVisualTransformation())
    } }, confirmButton = { Button(enabled = valid, onClick = { save(current, next, confirm) }) { Text("Change Password") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun ProfileLine(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value) } }

@Composable private fun ProfileEditDialog(profile: AffiliateProfile, close: () -> Unit, save: (JsonObject) -> Unit) {
    var name by remember { mutableStateOf(profile.fullName) }; var age by remember { mutableStateOf(profile.age.toString()) }; var gender by remember { mutableStateOf(profile.gender) }; var education by remember { mutableStateOf(profile.education) }; var address by remember { mutableStateOf(profile.address) }; var site by remember { mutableStateOf(profile.siteAddress) }; var method by remember { mutableStateOf(profile.withdrawalMethod) }; var number by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Edit Profile") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Full Name") }); OutlinedTextField(age, { age = it.filter(Char::isDigit) }, label = { Text("Age") }); Text("Gender"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("MALE" to "Male", "FEMALE" to "Female").forEach { (value, label) -> FilterChip(gender == value, { gender = value }, { Text(label) }) } }; Text("Education"); Row { listOf("SSC", "HSC", "Diploma", "Undergraduate", "Graduate", "Postgraduate", "Other").forEach { if (it == education) AssistChip({}, { Text(it) }) } }; OutlinedTextField(education, { education = it }, label = { Text("Qualification") }); OutlinedTextField(address, { address = it }, label = { Text("Address") }); OutlinedTextField(site, { site = it }, label = { Text("Site Address") }); Row { listOf("bKash", "Nagad").forEach { option -> AssistChip(onClick = { method = option }, label = { Text(option) }, leadingIcon = if (method == option) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } }; OutlinedTextField(number, { number = it }, label = { Text("New $method Number (optional)") }) } }, confirmButton = { Button(enabled = gender in setOf("MALE", "FEMALE"), onClick = { save(buildJsonObject { put("full_name", name); put("age", age.toIntOrNull() ?: profile.age); put("gender", gender); put("education", education); put("address", address); put("site_address", site); put("withdrawal_method", method); if (number.isNotBlank()) put("withdrawal_number", number) }) }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun SettingsTitle(value: String) { HorizontalDivider(); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
@Composable private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, action: () -> Unit) { PremiumCard(Modifier.fillMaxWidth().clickable(onClick = action)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.size(12.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable private fun AffiliateEmpty(title: String, detail: String) { PremiumCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Assignment, null) }; Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun RowScope.WalletStat(label: String, value: String) { Card(Modifier.weight(1f)) { Column(Modifier.padding(14.dp)) { Text(label, style = MaterialTheme.typography.bodySmall); Text("$value coin", fontWeight = FontWeight.Bold) } } }

private fun label(field: TaskField) = field.label + if (field.required) " *" else ""
private fun displayValue(value: Any?): String = when (value) { is ProofValue -> value.name; is List<*> -> value.joinToString(); else -> value.toString() }
private fun valuesToJson(values: Map<String, Any?>): JsonObject = buildJsonObject { values.forEach { (key, value) -> when (value) { is String -> put(key, value); is Boolean -> put(key, value); is Number -> put(key, value.toDouble()); is List<*> -> put(key, JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive))); is ProofValue -> put(key, buildJsonObject { put("name", value.name); put("mime", value.mime); put("size", value.size); put("sha256", value.sha256); put("file_id", value.fileId) }) } } }
private suspend fun readProof(context: android.content.Context, uri: Uri, maxSizeMb: Int): ProofValue = withContext(Dispatchers.IO) {
    var name = "proof"; var size = 0L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) { name = it.getString(0) ?: name; size = if (it.isNull(1)) 0 else it.getLong(1) } }
    require(size <= maxSizeMb * 1024L * 1024L) { "Proof must be under $maxSizeMb MB" }
    val digest = MessageDigest.getInstance("SHA-256"); context.contentResolver.openInputStream(uri)?.use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count <= 0) break; digest.update(buffer, 0, count) } }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "proof" }
    val proofDir = File(context.filesDir, "Affiliate/proofs").apply { mkdirs() }
    val stored = File(proofDir, "${hash.take(16)}_$safeName")
    context.contentResolver.openInputStream(uri)?.use { input -> stored.outputStream().use(input::copyTo) }
    if (stored.length() > maxSizeMb * 1024L * 1024L) { stored.delete(); error("Proof must be under $maxSizeMb MB") }
    val logical = "proofs/${stored.name}"
    ProofValue(name.take(160), context.contentResolver.getType(uri) ?: "application/octet-stream", size, hash, uri.toString(), FileIds.encodeAppPrivate(logical))
}
private fun statusColor(status: String) = when (status) { "APPROVED" -> Color(0xFF4CAF78); "REJECTED" -> Color(0xFFE56B6F); "REVISION_REQUESTED" -> Color(0xFFF4B942); else -> Color(0xFF8899C6) }
private fun formatCoin(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').ifBlank { "0" }
private fun earnedSince(items: List<com.ownerlink.agent.affiliate.AffiliateLedgerItem>, days: Long): String { val since = Instant.now().minus(days, ChronoUnit.DAYS); return String.format(Locale.US, "%.3f", items.filter { runCatching { Instant.parse(it.createdAt).isAfter(since) }.getOrDefault(false) }.sumOf { it.amount.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0 }).trimEnd('0').trimEnd('.') }
private fun formatSize(value: Long): String = if (value >= 1024 * 1024) "%.1f MB".format(value / 1048576.0) else if (value >= 1024) "%.1f KB".format(value / 1024.0) else "$value B"
