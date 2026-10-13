package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.ownerlink.agent.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.affiliate.AffiliateAccountRules
import com.ownerlink.agent.affiliate.RegistrationDraft
import com.ownerlink.agent.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private enum class AuthPage { Welcome, Register, Login }

@Composable
internal fun AffiliateAuth(legacyName: String? = null) {
    val app = OwnerLinkApp.instance
    val busy by app.affiliateAccount.busy.collectAsState()
    val message by app.affiliateAccount.message.collectAsState()
    var page by remember { mutableStateOf(if (legacyName != null) AuthPage.Register else AuthPage.Welcome) }
    GlassBackdrop { AdaptivePane {
        AnimatedContent(page, label = "auth-page") { destination -> when (destination) {
            AuthPage.Welcome -> WelcomePage { page = it }
            AuthPage.Login -> LoginPage(busy, message, { app.affiliateAccount.clearMessage(); page = AuthPage.Welcome }) { phone, password -> app.affiliateAccount.login(phone, password) }
            AuthPage.Register -> RegistrationPage(legacyName.orEmpty(), busy, message, app.affiliateAccount::clearMessage, { app.affiliateAccount.clearMessage(); page = AuthPage.Welcome }, app.affiliateAccount::register)
        } }
    } }
}

@Composable private fun WelcomePage(open: (AuthPage) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).glassPanel(28), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.affiliate_symbol), "Affiliate logo", Modifier.size(70.dp)) }
        Spacer(Modifier.height(28.dp))
        Text("AFFILIATE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("A little progress.\nA new possibility.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Discover tasks, follow your activity and keep your rewards in one place.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().glassPanel(24).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Discover" to "Tasks", "Track" to "Activity", "Collect" to "Rewards").forEach { (title, subtitle) ->
                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(28.dp)); Button(onClick = { open(AuthPage.Register) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("Get started") }
        Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = { open(AuthPage.Login) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("I already have an account") }
    }
}

@Composable private fun LoginPage(busy: Boolean, message: String?, back: () -> Unit, login: (String, String) -> Unit) {
    var phone by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var show by remember { mutableStateOf(false) }
    AuthShell("Welcome back", "Login with your Affiliate account", back) {
        OutlinedTextField(phone, { phone = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("Phone Number") }, singleLine = true)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } })
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { login(phone, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { if (busy) CircularProgressIndicator(Modifier.size(22.dp)) else Text("Login") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RegistrationPage(legacyName: String, busy: Boolean, message: String?, clearMessage: () -> Unit, back: () -> Unit, register: (RegistrationDraft) -> Unit) {
    var step by remember { mutableIntStateOf(0) }; var draft by remember { mutableStateOf(RegistrationDraft(fullName = legacyName)) }; var showPassword by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current; val scope = rememberCoroutineScope(); var photoReady by remember { mutableStateOf(false) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        val encoded = withContext(Dispatchers.IO) { compressProfilePhoto(context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }) }
        if (encoded != null) { draft = draft.copy(profilePhotoBase64 = encoded, profilePhotoMime = "image/jpeg"); photoReady = true }
    } }
    val title = if (legacyName.isNotBlank()) "Complete Your Account" else "Create Account"
    AuthShell(title, "Step ${step + 1} / 5", back) {
        LinearProgressIndicator(progress = { (step + 1) / 5f }, Modifier.fillMaxWidth())
        AnimatedContent(step, label = "registration-step") { current -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { when (current) {
            0 -> { StepTitle("Personal Information"); Field(draft.fullName, { draft = draft.copy(fullName = it) }, "Full Name"); Field(draft.phoneNumber, { draft = draft.copy(phoneNumber = it) }, "Phone Number"); Field(draft.age, { draft = draft.copy(age = it.filter(Char::isDigit)) }, "Age"); Text("Gender", fontWeight = FontWeight.SemiBold); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("MALE" to "Male", "FEMALE" to "Female").forEach { (value, label) -> FilterChip(draft.gender == value, { draft = draft.copy(gender = value) }, { Text(label) }) } } }
            1 -> { StepTitle("Profile"); OutlinedButton(onClick = { photo.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) { Icon(if (photoReady) Icons.Rounded.CheckCircle else Icons.Rounded.Person, null); Spacer(Modifier.width(8.dp)); Text(if (photoReady) "Profile Photo Selected" else "Choose Profile Photo") }; Text("Educational Qualification", fontWeight = FontWeight.SemiBold); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("SSC", "HSC", "Diploma", "Undergraduate", "Graduate", "Postgraduate", "Other").forEach { option -> FilterChip(draft.education == option, { draft = draft.copy(education = option) }, { Text(option) }) } }; Field(draft.address, { draft = draft.copy(address = it) }, "Address", 2) }
            2 -> { StepTitle("Withdrawal Information"); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("bKash", "Nagad").forEach { option -> FilterChip(draft.withdrawalMethod == option, { draft = draft.copy(withdrawalMethod = option) }, { Text(option) }) } }; Field(draft.withdrawalNumber, { draft = draft.copy(withdrawalNumber = it) }, if (draft.withdrawalMethod.isBlank()) "Withdrawal Number" else "${draft.withdrawalMethod} Number"); Text("Only your payout number is requested. Never enter a PIN, OTP, or mobile-banking password.", style = MaterialTheme.typography.bodySmall) }
            3 -> { StepTitle("Security"); OutlinedTextField(draft.password, { draft = draft.copy(password = it) }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } }); OutlinedTextField(draft.confirmPassword, { draft = draft.copy(confirmPassword = it) }, Modifier.fillMaxWidth(), label = { Text("Confirm Password") }, visualTransformation = PasswordVisualTransformation()); Text("Use at least 8 characters with letters and numbers.", style = MaterialTheme.typography.bodySmall) }
            else -> RegistrationPermissionStep {
                clearMessage()
                val errors = AffiliateAccountRules.validate(draft)
                if (errors.isEmpty()) register(draft) else {
                    val first = errors.entries.first()
                    localMessage = first.value
                    step = registrationStepFor(first.key)
                }
            }
        } } }
        (localMessage ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) OutlinedButton(onClick = { localMessage = null; clearMessage(); step-- }, Modifier.weight(1f)) { Text("Back") }
            if (step < 4) Button(onClick = {
                clearMessage()
                val error = AffiliateAccountRules.validate(draft).entries.firstOrNull { registrationStepFor(it.key) == step }
                localMessage = error?.value
                if (error == null) step++
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}

private fun registrationStepFor(field: String): Int = when (field) {
    "fullName", "phone", "age", "gender" -> 0
    "education", "address", "siteAddress", "photo" -> 1
    "withdrawalMethod", "withdrawalNumber" -> 2
    "password", "confirmPassword" -> 3
    else -> 0
}

@Composable private fun RegistrationPermissionStep(finish: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteAccessSetup()
        Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Finish Create Account") }
    }
}

@Composable private fun AuthShell(title: String, subtitle: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = back) { Text("← Back") }; Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant); Box(Modifier.fillMaxWidth().glassPanel()) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}
@Composable private fun Field(value: String, update: (String) -> Unit, label: String, minLines: Int = 1) = OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)
@Composable private fun StepTitle(value: String) = Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
@Composable private fun Review(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value.take(36), fontWeight = FontWeight.SemiBold) } }
private fun mask(value: String): String { val d = value.filter(Char::isDigit); return if (d.length >= 5) d.take(2) + "******" + d.takeLast(3) else "••••••" }
internal fun compressProfilePhoto(bitmap: Bitmap?): String? { bitmap ?: return null; val ratio = minOf(1f, 640f / maxOf(bitmap.width, bitmap.height)); val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true) else bitmap; val output = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 78, output); return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP) }
