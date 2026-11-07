package com.clotube.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.ui.utils.NativeDarkFilePicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

@Composable
fun DesktopSetupWizardScreen(
    onConfigured: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var previousStep by remember { mutableIntStateOf(1) }

    // Scoped strictly to Step 5
    var stepFiveStatusMessage by remember { mutableStateOf<String?>(null) }
    var stepFiveIsSuccess by remember { mutableStateOf(false) }

    // Errors related to opening links in browser (Steps 1-4)
    var stepError by remember { mutableStateOf<String?>(null) }

    var testerEmail by remember { mutableStateOf("") }
    var emailCopied by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    fun openBrowser(url: String) {
        try {
            stepError = null
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            stepError = "Could not open browser: ${e.message}"
        }
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        try {
            val selection = StringSelection(text.trim())
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            emailCopied = true
            coroutineScope.launch {
                delay(2000L)
                emailCopied = false
            }
        } catch (e: Exception) {
            stepError = "Clipboard error: ${e.message}"
        }
    }

    fun pickAndSaveSecretsFile() {
        NativeDarkFilePicker.pickJsonFile("Select Google Cloud client_secret_*.json") { selectedFile ->
            val result = DesktopAuthManager.saveClientSecrets(selectedFile)
            if (result.isSuccess) {
                stepFiveStatusMessage = "Successfully imported '${selectedFile.name}'! Credentials configured."
                stepFiveIsSuccess = true
            } else {
                stepFiveStatusMessage = result.exceptionOrNull()?.message ?: "Failed to validate credentials file."
                stepFiveIsSuccess = false
            }
        }
    }

    fun goToStep(target: Int) {
        if (target in 1..5 && target != currentStep) {
            stepError = null
            previousStep = currentStep
            currentStep = target
        }
    }

    val isMovingForward = currentStep >= previousStep

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07090E)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(860.dp)
                .fillMaxHeight(0.92f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D111A)),
            border = BorderStroke(1.dp, Color(0xFF1B2234))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp, vertical = 28.dp)
            ) {
                // Header & Stepper Indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(0xFF00E5FF), RoundedCornerShape(5.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "GOOGLE CLOUD SETUP WIZARD",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }

                    Text(
                        text = "STEP $currentStep OF 5",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Progress Bar Segments
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (step in 1..5) {
                        val isActive = step == currentStep
                        val isCompleted = step < currentStep || (step == 5 && (stepFiveIsSuccess || DesktopAuthManager.hasClientSecrets()))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        isCompleted -> Color(0xFF10B981)
                                        isActive -> Color(0xFF00E5FF)
                                        else -> Color(0xFF1F293D)
                                    }
                                )
                                .clickable { goToStep(step) }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Animated Slide Container
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            if (isMovingForward) {
                                (slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(420, easing = FastOutSlowInEasing)
                                ) + fadeIn(tween(300))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -fullWidth },
                                        animationSpec = tween(420, easing = FastOutSlowInEasing)
                                    ) + fadeOut(tween(240))
                                )
                            } else {
                                (slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth },
                                    animationSpec = tween(420, easing = FastOutSlowInEasing)
                                ) + fadeIn(tween(300))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> fullWidth },
                                        animationSpec = tween(420, easing = FastOutSlowInEasing)
                                    ) + fadeOut(tween(240))
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { targetStep ->
                        when (targetStep) {
                            1 -> StepOneView(onOpenConsole = { openBrowser("https://console.cloud.google.com/projectcreate") })
                            2 -> StepTwoView(onOpenApiLibrary = { openBrowser("https://console.cloud.google.com/apis/library/drive.googleapis.com") })
                            3 -> StepThreeView(
                                testerEmail = testerEmail,
                                onEmailChange = { testerEmail = it },
                                emailCopied = emailCopied,
                                onCopyEmail = { copyToClipboard(testerEmail) },
                                onOpenConsentScreen = { openBrowser("https://console.cloud.google.com/apis/credentials/consent") }
                            )
                            4 -> StepFourView(onOpenCredentials = { openBrowser("https://console.cloud.google.com/apis/credentials") })
                            5 -> StepFiveView(
                                onPickFile = { pickAndSaveSecretsFile() },
                                statusMessage = stepFiveStatusMessage,
                                isSuccess = stepFiveIsSuccess,
                                hasExistingSecrets = DesktopAuthManager.hasClientSecrets(),
                                clientSecretsPath = DesktopAuthManager.getClientSecretsFile().absolutePath
                            )
                        }
                    }
                }

                // Temporary operational error banner (e.g. browser launch failure)
                if (stepError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF331419), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(text = stepError!!, color = Color(0xFFFEE2E2), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Navigation Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Back button & Manual Verify link
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { goToStep(currentStep - 1) },
                            enabled = currentStep > 1,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (currentStep > 1) Color(0xFF26334D) else Color(0xFF161C26)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFCBD5E1),
                                disabledContentColor = Color(0xFF475569)
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.pointerHoverIcon(if (currentStep > 1) PointerIcon.Hand else PointerIcon.Default)
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Previous", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Text(
                            text = "Check existing config",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    if (DesktopAuthManager.hasClientSecrets()) {
                                        onConfigured()
                                    } else {
                                        stepError = "No credentials found in ${DesktopAuthManager.getClientSecretsFile().absolutePath}"
                                    }
                                }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                    }

                    // Right: Next button or Finish button
                    if (currentStep < 5) {
                        Button(
                            onClick = { goToStep(currentStep + 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Text(text = "Next Step", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Button(
                            onClick = onConfigured,
                            enabled = stepFiveIsSuccess || DesktopAuthManager.hasClientSecrets(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                disabledContainerColor = Color(0xFF1E2638)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                            modifier = Modifier.pointerHoverIcon(
                                if (stepFiveIsSuccess || DesktopAuthManager.hasClientSecrets()) PointerIcon.Hand else PointerIcon.Default
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (stepFiveIsSuccess || DesktopAuthManager.hasClientSecrets()) Color.Black else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Continue to Sign In",
                                color = if (stepFiveIsSuccess || DesktopAuthManager.hasClientSecrets()) Color.Black else Color(0xFF64748B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepOneView(onOpenConsole: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Step 1: Create a Google Cloud Project", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Every Clotube desktop installation uses a private Google Cloud project to talk to Google Drive API directly with zero shared quotas and maximum privacy.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111726)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WizardBulletItem(number = "1", title = "Log in with your Google account", description = "Make sure you are logged into the Google account that hosts your media Drive.")
                WizardBulletItem(number = "2", title = "Click 'New Project'", description = "Name the project 'Clotube Media' or any title of your choice.")
                WizardBulletItem(number = "3", title = "Select the new project", description = "Once created, verify the top project selector dropdown is pointing to your new project.")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onOpenConsole,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Open Google Cloud Console (Project Creator)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepTwoView(onOpenApiLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Step 2: Enable the Google Drive API", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The Google Drive API grants Clotube permission to scan your video files, stream high-bitrate media, and sync playback watch history.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111726)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WizardBulletItem(number = "1", title = "Verify Project Selection", description = "Ensure your new Clotube project is actively selected in the top bar.")
                WizardBulletItem(number = "2", title = "Visit Google Drive API in API Library", description = "Click the button below to jump straight to the Google Drive API listing.")
                WizardBulletItem(number = "3", title = "Click the blue 'ENABLE' button", description = "Wait a few seconds for the status to switch to 'API Enabled'.")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onOpenApiLibrary,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Open Google Drive API Library Page", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepThreeView(
    testerEmail: String,
    onEmailChange: (String) -> Unit,
    emailCopied: Boolean,
    onCopyEmail: () -> Unit,
    onOpenConsentScreen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Step 3: OAuth Consent Screen & Test Users", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Configure how Google prompts for authorization and register your email address so Google allows your account to log in.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF261806), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MANDATORY TO AVOID 'ERROR 403: ACCESS_DENIED'",
                        color = Color(0xFFFCD34D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Personal Google Cloud projects run in 'Testing' mode. Google blocks any account that is not explicitly registered in the 'Audience' / 'Test users' table.",
                    color = Color(0xFFFDE68A),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0F141F),
                        border = BorderStroke(1.dp, Color(0xFF38445E))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (testerEmail.isEmpty()) {
                                Text(text = "Enter your Google account email here...", color = Color(0xFF64748B), fontSize = 12.sp)
                            }
                            BasicTextField(
                                value = testerEmail,
                                onValueChange = onEmailChange,
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onCopyEmail,
                        enabled = testerEmail.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (emailCopied) Color(0xFF10B981) else Color(0xFFF59E0B)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .pointerHoverIcon(if (testerEmail.isNotBlank()) PointerIcon.Hand else PointerIcon.Default)
                    ) {
                        Icon(imageVector = if (emailCopied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (emailCopied) "Copied!" else "Copy Email", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111726)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WizardBulletItem(number = "1", title = "Select 'External' User Type", description = "Click 'External' and then click 'Create'.")
                WizardBulletItem(number = "2", title = "Enter Basic Information", description = "App name: 'Clotube', User support email: your email.")
                WizardBulletItem(number = "3", title = "Add to 'Test users'", description = "Navigate to 'Audience' (or Step 3 'Test users'), click '+ ADD USERS', paste your email from above, and click Save.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenConsentScreen,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Open OAuth Consent Screen & Audience Tab", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepFourView(onOpenCredentials: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Step 4: Create Desktop OAuth Client ID", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Generate the OAuth credentials that will allow your local Clotube desktop client to securely negotiate authorization tokens.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111726)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WizardBulletItem(number = "1", title = "Go to Credentials", description = "Click the button below to open the Credentials tab in Google Cloud Console.")
                WizardBulletItem(number = "2", title = "Click '+ CREATE CREDENTIALS'", description = "Select 'OAuth client ID' from the dropdown menu.")
                WizardBulletItem(number = "3", title = "Set Application type to 'Desktop app'", description = "Name it 'Clotube Desktop Client' and click the blue 'Create' button.")
                WizardBulletItem(number = "4", title = "Dismiss popup dialog", description = "When the 'OAuth client created' modal appears, click OK.")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onOpenCredentials,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Open Credentials Dashboard", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepFiveView(
    onPickFile: () -> Unit,
    statusMessage: String?,
    isSuccess: Boolean,
    hasExistingSecrets: Boolean,
    clientSecretsPath: String
) {
    val maskedClientId = remember(hasExistingSecrets, clientSecretsPath) {
        if (hasExistingSecrets) {
            try {
                val file = File(clientSecretsPath)
                if (file.exists()) {
                    val content = file.readText()
                    val match = Regex("\"client_id\"\\s*:\\s*\"([^\"]+)\"").find(content)
                    val clientId = match?.groupValues?.get(1)
                    if (clientId != null && clientId.length > 8) {
                        "..." + clientId.takeLast(8)
                    } else null
                } else null
            } catch (_: Exception) { null }
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Step 5: Download & Import client_secrets.json", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Download the OAuth JSON key from your Google Cloud Console Credentials table and import it into Clotube.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111726)),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WizardBulletItem(number = "1", title = "Locate your Client ID", description = "In the Credentials list under 'OAuth 2.0 Client IDs', find your Desktop client.")
                WizardBulletItem(number = "2", title = "Click Download JSON (⬇)", description = "Click the download icon on the far right of the row to save client_secret_*.json.")
                WizardBulletItem(number = "3", title = "Import into Clotube", description = "Click the button below to browse and select the downloaded JSON file.")
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onPickFile,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Browse & Select client_secrets.json", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // STATUS ONLY APPEARS ON STEP 5
        if (statusMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSuccess) Color(0xFF0F2D1F) else Color(0xFF331419),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isSuccess) "Credentials Configured Successfully" else "Import Failed",
                            color = if (isSuccess) Color(0xFFD1FAE5) else Color(0xFFFEE2E2),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusMessage,
                            color = if (isSuccess) Color(0xFFA7F3D0) else Color(0xFFFECACA),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        } else if (hasExistingSecrets) {
            // Informational badge if returning to wizard when secrets already exist
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F1B2A),
                border = BorderStroke(1.dp, Color(0xFF1E3A5F)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (maskedClientId != null) {
                            "Current credentials loaded: Client ID ending in $maskedClientId. You can upload a new JSON above to replace them."
                        } else {
                            "Existing Google Cloud credentials detected. You can import a new file to replace them or click Continue below."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardBulletItem(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF1A2333), CircleShape)
                .border(1.dp, Color(0xFF2E3E5C), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = number, color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = Color(0xFF8E9BAE), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
