package com.ownerlink.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.R
import com.ownerlink.agent.affiliate.AffiliateAuthState
import com.ownerlink.agent.affiliate.AffiliateTaskItem
import com.ownerlink.agent.network.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val photoClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()

/** Size-bound decoding keeps original camera photos out of Compose's bitmap memory. */
private fun decodeSmall(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
internal fun dashboardPhoto(url: String?, privatePhoto: Boolean = false): Bitmap? {
    val app = OwnerLinkApp.instance
    val photo by produceState<Bitmap?>(null, url, privatePhoto) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (url.isNullOrBlank()) return@runCatching null
                val base = Uri.parse(app.store.serverUrl)
                val resolved = if (url.startsWith("/")) app.store.serverUrl + url else url
                val uri = Uri.parse(resolved)
                if (uri.scheme != "https") return@runCatching null
                if (privatePhoto && uri.host != base.host) return@runCatching null
                val request = Request.Builder().url(resolved)
                if (privatePhoto) app.affiliateAccount.token()?.let { request.header("Authorization", "Bearer $it") }
                photoClient.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    val bytes = body.byteStream().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= 2 * 1024 * 1024) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size > 2 * 1024 * 1024) null else decodeSmall(bytes)
                }
            }.getOrNull()
        }
    }
    return photo
}

@Composable
internal fun AffiliateBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.affiliate_symbol), "Affiliate logo", Modifier.size(38.dp))
        Text("Affiliate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun FrostedDashboard(open: (AffiliateTaskItem) -> Unit, activity: () -> Unit, profile: () -> Unit) {
    val app = OwnerLinkApp.instance
    val state by app.affiliate.state.collectAsState()
    val account by app.affiliateAccount.state.collectAsState()
    val socket by app.socket.state.collectAsState()
    val user = (account as? AffiliateAuthState.SignedIn)?.profile
    val name = user?.fullName ?: app.store.friendlyName.ifBlank { "Affiliate user" }
    val photo = dashboardPhoto(user?.profilePhotoUrl, true)
    var selected by remember { mutableStateOf("All") }
    fun platform(task: AffiliateTaskItem): String {
        val host = runCatching { Uri.parse(task.actionUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
            host.endsWith("instagram.com") -> "Instagram"
            host.endsWith("tiktok.com") -> "TikTok"
            host.endsWith("facebook.com") -> "Facebook"
            task.taskType == "URL" -> "Links"
            else -> "Forms"
        }
    }
    val submitted = state.submissions.map { it.taskId }.toSet()
    val available = state.tasks.filter { it.status == "PUBLISHED" && it.taskId !in submitted }
    val platforms = listOf("All") + available.map(::platform).distinct()
    val visible = available.filter { selected == "All" || platform(it) == selected }
    val pending = state.submissions.count { it.status in setOf("PENDING_REVIEW", "SUBMITTED") }
    val approved = state.submissions.count { it.status == "APPROVED" }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 360.dp && fontScale < 1.3f) 2 else 1
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { AffiliateBrand() }
                    IconButton(onClick = activity) { Icon(Icons.Rounded.NotificationsNone, "Open activity") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable(onClick = profile), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        if (photo != null) Image(photo.asImageBitmap(), "Your profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Welcome back,", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().glassPanel().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your balance", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AnimatedContent(state.balance, label = "dashboard-balance") { Text(it, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold) }
                            Text("coins", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        }
                        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Rewards after approval", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().glassPanel(24).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardStat("Tasks", available.size, Icons.Rounded.Assignment, Color(0xFFFF6776))
                    DashboardStat("In review", pending, Icons.Rounded.Schedule, Color(0xFF368CDF))
                    DashboardStat("Approved", approved, Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today's opportunities", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { selected = "All" }) { Text("See all") }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(platforms) { label -> FilterChip(selected == label, { selected = label }, label = { Text(label) }, shape = RoundedCornerShape(50)) }
                }
            }
            if (state.loading) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (!state.loading && visible.isEmpty()) item {
                Text("No available tasks here yet. Published tasks appear after sync.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible.chunked(columns), key = { row -> row.joinToString { it.taskId } }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { task -> FrostedTaskTile(task, platform(task), Modifier.weight(1f), open) }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().glassPanel(24).clickable(onClick = activity).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, tint = Color(0xFF368CDF))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Your activity", fontWeight = FontWeight.Bold)
                        Text(if (pending > 0) "$pending submissions pending review" else "Track your submissions and rewards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
            if (socket !in setOf(ConnectionState.Online, ConnectionState.WaitingForPair)) item {
                Text("Offline · showing saved tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RowScope.DashboardStat(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FrostedTaskTile(task: AffiliateTaskItem, platform: String, modifier: Modifier, open: (AffiliateTaskItem) -> Unit) {
    val uri = runCatching { Uri.parse(task.actionUrl) }.getOrNull()
    val videoId = if (platform == "YouTube") uri?.getQueryParameter("v") ?: uri?.lastPathSegment else null
    val thumbnail = dashboardPhoto(videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" })
    val accent = when(platform) { "YouTube" -> Color(0xFFE63545); "Instagram" -> Color(0xFFBA3990); else -> MaterialTheme.colorScheme.primary }
    Column(modifier.glassPanel(24).clickable { open(task) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = .14f), MaterialTheme.colorScheme.secondaryContainer))), contentAlignment = Alignment.Center) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), "Video thumbnail", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (task.taskType == "URL") Icons.Rounded.PlayCircle else Icons.Rounded.Description, null, Modifier.size(48.dp), tint = accent)
            Text(platform, Modifier.align(Alignment.BottomStart).padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(30.dp)).padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = accent)
        }
        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (task.taskType == "URL") "Video / link task" else "Response task", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${task.reward} coins", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = { open(task) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (task.taskType == "URL") "Watch video" else "Open task", style = MaterialTheme.typography.labelLarge)
        }
    }
}
