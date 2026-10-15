package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.services.CrashReporter
import com.focusflow.services.ReviewPromptService
import com.focusflow.services.AppLifecycle
import com.focusflow.services.AutoBackupService
import com.focusflow.services.BackupEntry
import com.focusflow.services.BackupLocation
import com.focusflow.services.BackupRecoveryService
import com.focusflow.services.RestoreArchiveDeleteResult
import com.focusflow.services.RestoreArchiveEntry
import com.focusflow.services.RestoreScheduleResult
import com.focusflow.services.SafetyBackupDeleteResult
import com.focusflow.services.SecondaryBackupDeleteResult
import com.focusflow.services.SecondaryCopyStatus
import com.focusflow.services.SecondaryLocationUpdate
import com.focusflow.ui.components.openUrl
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.awt.Desktop
import java.net.URI
import javax.swing.JFileChooser

@Composable
fun ContactScreen() {
    val scope       = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var crashLogs      by remember { mutableStateOf(listOf<File>()) }
    var selectedLog    by remember { mutableStateOf<File?>(null) }
    var logPreview     by remember { mutableStateOf("") }
    var showPreview    by remember { mutableStateOf(false) }
    var statusMessage  by remember { mutableStateOf("") }
    var feedbackText   by remember { mutableStateOf("") }
    var feedbackSent   by remember { mutableStateOf(false) }
    var feedbackSending by remember { mutableStateOf(false) }
    var feedbackFailed  by remember { mutableStateOf(false) }
    var showForgottenPinNotice by rememberSaveable { mutableStateOf(true) }
    var showFeedbackNotice by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { CrashReporter.findCrashLogs() }
    }

    fun openLogFolder(file: File) {
        try {
            Desktop.getDesktop().open(file.parentFile ?: file)
        } catch (_: Throwable) {
            statusMessage = "Log location: ${file.absolutePath}"
        }
    }

    fun refreshLogs() {
        scope.launch {
            crashLogs = withContext(Dispatchers.IO) { CrashReporter.findCrashLogs() }
        }
    }

    fun emailLog(file: File) {
        scope.launch {
            try {
                // Step 1: open the folder so the user can drag-attach the file
                openLogFolder(file)

                // Step 2: open email with a short body — most clients reject mailto: URIs
                // with thousands of characters, so we keep it brief and ask the user to attach.
                val subject = "FocusFlow Crash Report – ${file.name}"
                val body = buildString {
                    appendLine("Hi FocusFlow Support,")
                    appendLine()
                    appendLine("I'm experiencing a crash. Please find the log file attached.")
                    appendLine()
                    appendLine("App version : ${CrashReporter.APP_VERSION}")
                    appendLine("Log file    : ${file.name}")
                    appendLine()
                    appendLine("(The log folder just opened — please attach the file shown there)")
                }
                val encoded    = java.net.URLEncoder.encode(body, "UTF-8").replace("+", "%20")
                val subjectEnc = java.net.URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
                val uri = URI("mailto:${CrashReporter.SUPPORT_EMAIL}?subject=$subjectEnc&body=$encoded")
                Desktop.getDesktop().mail(uri)
                statusMessage = "Log folder opened. Drag the log file into the email draft."
            } catch (_: Throwable) {
                openUrl("mailto:${CrashReporter.SUPPORT_EMAIL}?subject=FocusFlow+Crash+Report")
                statusMessage = "Email client opened. Attach the log file: ${file.absolutePath}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ── Header ──────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SupportAgent, contentDescription = null, tint = Purple80, modifier = Modifier.size(28.dp))
                Column {
                    Text("Contact & Bug Reports", style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface, fontWeight = FontWeight.Bold)
                    Text("Report crashes or get in touch with support",
                        style = MaterialTheme.typography.bodyMedium, color = OnSurface2)
                }
            }

            // ── Status banner ───────────────────────────────────────────────────
            if (statusMessage.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Success.copy(alpha = 0.12f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(18.dp))
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = OnSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, contentDescription = "Dismiss",
                        tint = OnSurface2, modifier = Modifier.size(16.dp).clickable { statusMessage = "" })
                }
            }

            // ── Contact buttons ─────────────────────────────────────────────────
            SectionCard(title = "Get in Touch", icon = Icons.Default.Email) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ContactRow(
                        icon     = Icons.Default.Email,
                        label    = "Email Support",
                        sublabel = CrashReporter.SUPPORT_EMAIL,
                        onClick  = { openUrl("mailto:${CrashReporter.SUPPORT_EMAIL}") }
                    )
                    ContactRow(
                        icon     = Icons.Default.BugReport,
                        label    = "Report a Bug on GitHub",
                        sublabel = "github.com/TITANICBHAI/FocusFlow-PC/issues",
                        onClick  = { openUrl("https://github.com/TITANICBHAI/FocusFlow-PC/issues/new") }
                    )
                    ContactRow(
                        icon     = Icons.Default.Star,
                        label    = "Rate Us on Microsoft Store",
                        sublabel = "Opens the FocusFlow review page in the Store",
                        onClick  = { ReviewPromptService.onRateNow() }
                    )
                }
            }

            if (showForgottenPinNotice) {
                SectionCard(
                    title = "Forgotten Global PIN",
                    icon = Icons.Default.Lock,
                    trailing = {
                        IconButton(
                            onClick = { showForgottenPinNotice = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss forgotten PIN notice",
                                tint = OnSurface2,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                ) {
                    Text(
                        "FocusFlow stores only a hash, so neither FocusFlow nor Support can retrieve or reset a forgotten PIN. " +
                        "The in-app “Forgot PIN?” option is not currently a usable recovery path. Protected changes may remain inaccessible, so keep your PIN somewhere safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Direct feedback ─────────────────────────────────────────────────
            if (ReviewPromptService.feedbackEnabled) {
                SectionCard(title = "Send Feedback", icon = Icons.Default.Feedback) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Got a suggestion or ran into something odd? Send it directly to the team.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2,
                            lineHeight = 18.sp
                        )
                        if (showFeedbackNotice) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Purple80.copy(alpha = 0.08f))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Purple80,
                                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "One-way feedback",
                                        color = OnSurface,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "We can read what you send, but this form has no reply address, so we can’t respond. " +
                                        "For a reply, use Email Support or GitHub Issues in Get in Touch above.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface2,
                                        lineHeight = 18.sp
                                    )
                                }
                                IconButton(
                                    onClick = { showFeedbackNotice = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss feedback notice",
                                        tint = OnSurface2,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (feedbackSent) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Success.copy(alpha = 0.10f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = Success, modifier = Modifier.size(16.dp))
                                Text("Feedback sent — thanks!", color = Success,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { feedbackSent = false; feedbackText = "" },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("Send another", fontSize = 12.sp) }
                            }
                        } else {
                            OutlinedTextField(
                                value         = feedbackText,
                                 onValueChange = { feedbackText = it; feedbackFailed = false },
                                placeholder   = { Text("Describe your issue or idea…", color = OnSurface2) },
                                minLines      = 3,
                                maxLines      = 8,
                                modifier      = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Purple80,
                                    unfocusedBorderColor = OnSurface2.copy(alpha = 0.3f)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        feedbackSending = true
                                         feedbackFailed  = false
                                         ReviewPromptService.sendFeedback(feedbackText) { delivered ->
                                             feedbackSending = false
                                             feedbackSent    = delivered
                                             feedbackFailed  = !delivered
                                             if (!delivered) statusMessage = "Feedback could not be delivered. Please try again."
                                         }
                                    },
                                    enabled = feedbackText.isNotBlank() && !feedbackSending,
                                    colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null,
                                        modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Send Feedback", fontSize = 13.sp)
                                }
                             if (feedbackFailed) {
                                 Text(
                                     "Discord did not accept the feedback. Please try again.",
                                     color = MaterialTheme.colorScheme.error,
                                     style = MaterialTheme.typography.bodySmall
                                 )
                             }
                            }
                        }
                    }
                }
            }

            // ── Crash logs ──────────────────────────────────────────────────────
            SectionCard(
                title = "Crash Logs",
                icon  = Icons.AutoMirrored.Filled.Article,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { refreshLogs() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh", fontSize = 12.sp)
                        }
                    }
                }
            ) {
                if (crashLogs.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface3)
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = Success, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("No crash logs found – all good!", color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${crashLogs.size} crash log${if (crashLogs.size != 1) "s" else ""} found",
                            style = MaterialTheme.typography.bodySmall, color = OnSurface2)

                        crashLogs.forEach { log ->
                            CrashLogRow(
                                file      = log,
                                isSelected = selectedLog == log,
                                onEmail   = { emailLog(log) },
                                onOpenFolder = { openLogFolder(log) },
                                onPreview = {
                                    scope.launch {
                                        val text = withContext(Dispatchers.IO) {
                                            try { log.readText(Charsets.UTF_8).take(3000) }
                                            catch (_: Throwable) { "Could not read file." }
                                        }
                                        selectedLog  = log
                                        logPreview   = text
                                        showPreview  = true
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Help tip ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Purple80.copy(alpha = 0.08f))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Purple80, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How to send a crash report", color = Purple80,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Click \"Email Report\" next to a log file. Your email client will open with the crash details pre-filled. " +
                        "For best results, also attach the log file manually — click \"Open Folder\" to find it.",
                        color = OnSurface2, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
        )
    }

    // ── Log preview dialog ───────────────────────────────────────────────────────
    if (showPreview && selectedLog != null) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showPreview = false; selectedLog?.let { emailLog(it) } }) {
                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Email Report")
                    }
                    TextButton(onClick = { showPreview = false }) { Text("Close") }
                }
            },
            title = {
                Text(selectedLog?.name ?: "Crash Log", fontWeight = FontWeight.Bold, color = OnSurface)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface3)
                        .padding(12.dp)
                ) {
                    val previewScroll = rememberScrollState()
                    Text(
                        logPreview + if (logPreview.length >= 3000) "\n\n[...preview truncated]" else "",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = OnSurface2,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(previewScroll)
                    )
                }
            },
            containerColor = Surface2,
            titleContentColor = OnSurface
        )
    }
}

@Composable
internal fun BackupRecoverySection() {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var restoreArchives by remember { mutableStateOf<List<RestoreArchiveEntry>>(emptyList()) }
    var secondaryDirectory by remember { mutableStateOf<File?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf(false) }
    var restoreSource by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteSource by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteArchive by remember { mutableStateOf<RestoreArchiveEntry?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val state = withContext(Dispatchers.IO) {
                    BackupRecoverySnapshot(
                        secondaryDirectory = BackupRecoveryService.secondaryBackupDirectory(),
                        backups = BackupRecoveryService.listBackupEntries(),
                        pendingRestore = BackupRecoveryService.hasPendingRestore(),
                        restoreArchives = BackupRecoveryService.listRestoreArchives()
                    )
                }
                secondaryDirectory = state.secondaryDirectory
                entries = state.backups
                pendingRestore = state.pendingRestore
                restoreArchives = state.restoreArchives
            } catch (failure: Exception) {
                statusMessage = "Could not load backup status: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun chooseSecondaryFolder() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a secondary FocusFlow backup folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile ?: return
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.configureSecondaryBackupDirectory(selected)
            }
            when (result) {
                is SecondaryLocationUpdate.Saved -> {
                    secondaryDirectory = result.directory
                    statusMessage = "Secondary backups will be copied to ${result.directory.absolutePath}. Existing files are kept."
                }
                SecondaryLocationUpdate.Removed -> {
                    secondaryDirectory = null
                    statusMessage = "Secondary backup folder removed. Existing backup files were left untouched."
                }
                is SecondaryLocationUpdate.Failed -> statusMessage = result.reason
            }
            refresh()
            working = false
        }
    }

    fun saveBackupNow() {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) { AutoBackupService.runBackupNow() }
            statusMessage = when (result) {
                is AutoBackupService.BackupResult.Success -> when (val secondary = result.secondaryCopy) {
                    SecondaryCopyStatus.NotConfigured ->
                        "Verified backup saved to FocusFlow's app-data folder."
                    is SecondaryCopyStatus.Copied ->
                        "Verified backup saved to app data and copied to ${secondary.file.parent}."
                    is SecondaryCopyStatus.AlreadyPresent ->
                        "Verified app-data backup is current; its secondary copy is already present."
                    is SecondaryCopyStatus.Failed ->
                        "Primary backup is verified, but the secondary copy failed: ${secondary.reason}"
                }
                AutoBackupService.BackupResult.NoDatabase ->
                    "No open database is available to back up."
                AutoBackupService.BackupResult.VerificationFailed ->
                    "The backup did not pass verification. The current database was not changed."
                is AutoBackupService.BackupResult.Error ->
                    "Backup failed: ${result.reason}"
            }
            refresh()
            working = false
        }
    }

    fun stageRestore(entry: BackupEntry) {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.scheduleRestore(entry.file)
            }
            when (result) {
                is RestoreScheduleResult.Scheduled ->
                    statusMessage = "Restore of ${result.sourceName} is staged. FocusFlow will make and verify a fresh safety backup before applying it on the next launch."
                RestoreScheduleResult.AlreadyPending ->
                    statusMessage = "A restore is already staged. Quit and reopen FocusFlow to apply it."
                is RestoreScheduleResult.Rejected ->
                    statusMessage = result.reason
                is RestoreScheduleResult.Failed ->
                    statusMessage = result.reason
            }
            refresh()
            working = false
        }
    }

    fun deleteSecondaryBackup(entry: BackupEntry) {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.deleteSecondaryBackup(entry.file)
            }
            statusMessage = when (result) {
                SecondaryBackupDeleteResult.Deleted ->
                    "Secondary backup and its FocusFlow sidecars were removed. No other folder contents were touched."
                SecondaryBackupDeleteResult.NotInSecondaryFolder ->
                    "That file is not a removable FocusFlow backup in the selected secondary folder."
                is SecondaryBackupDeleteResult.Failed ->
                    result.reason
            }
            deleteSource = null
            refresh()
            working = false
        }
    }

    fun deleteSafetyBackup(entry: BackupEntry) {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.deleteSafetyBackup(entry.file)
            }
            statusMessage = when (result) {
                SafetyBackupDeleteResult.Deleted ->
                    "The selected safety backup and its sidecars were removed. The live database was not changed."
                SafetyBackupDeleteResult.NotSafetyBackup ->
                    "That file is not a FocusFlow safety backup."
                is SafetyBackupDeleteResult.Failed -> result.reason
            }
            deleteSource = null
            refresh()
            working = false
        }
    }

    fun deleteRestoreArchive(archive: RestoreArchiveEntry) {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                BackupRecoveryService.deleteRestoreArchive(archive.directory)
            }
            statusMessage = when (result) {
                RestoreArchiveDeleteResult.Deleted ->
                    "The selected recovery archive was removed. No active database or secondary backup was changed."
                RestoreArchiveDeleteResult.NotManagedArchive ->
                    "That folder is not a FocusFlow recovery archive."
                is RestoreArchiveDeleteResult.Failed -> result.reason
            }
            deleteArchive = null
            refresh()
            working = false
        }
    }

    SectionCard(title = "Backup & Recovery", icon = Icons.Default.Storage) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Regular app-data backups keep the latest 7 snapshots. Safety backups, pre-restore archives, and secondary-folder backups are never deleted automatically. Use a delete button to remove a specific saved copy.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface2,
                lineHeight = 18.sp
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Secondary folder",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    secondaryDirectory?.absolutePath ?: "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2,
                    maxLines = 2
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = ::chooseSecondaryFolder, enabled = !working) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (secondaryDirectory == null) "Choose folder" else "Change folder")
                }
                if (secondaryDirectory != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                working = true
                                val result = withContext(Dispatchers.IO) {
                                    BackupRecoveryService.configureSecondaryBackupDirectory(null)
                                }
                                when (result) {
                                    SecondaryLocationUpdate.Removed -> {
                                        secondaryDirectory = null
                                        statusMessage = "Secondary folder removed. Existing backups were left untouched."
                                    }
                                    is SecondaryLocationUpdate.Failed -> statusMessage = result.reason
                                    is SecondaryLocationUpdate.Saved -> Unit
                                }
                                refresh()
                                working = false
                            }
                        },
                        enabled = !working
                    ) { Text("Remove folder") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::saveBackupNow, enabled = !working) {
                    Text("Back up now")
                }
            }

            if (pendingRestore) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Purple80.copy(alpha = 0.10f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Purple80, modifier = Modifier.size(18.dp))
                    Text(
                        "A verified restore is staged. Quit and reopen FocusFlow to apply it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val shutdown = AppLifecycle.shutdown
                            if (shutdown == null) {
                                statusMessage = "The app's safe shutdown handler is not available. Quit FocusFlow normally, then reopen it."
                            } else {
                                shutdown(false, false)
                            }
                        },
                        enabled = !working,
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) {
                        Text("Quit to apply")
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusMessage.contains("failed", ignoreCase = true)) MaterialTheme.colorScheme.error else Success,
                    lineHeight = 18.sp
                )
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (entries.isEmpty()) {
                Text("No backup files found.", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            } else {
                val latestBackupCreatedAt = entries.maxOfOrNull { it.createdAtMillis }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        BackupEntryRow(
                            entry = entry,
                            isLatest = entry.createdAtMillis == latestBackupCreatedAt,
                            enabled = !working && !pendingRestore,
                            onRestore = { restoreSource = entry },
                            onDelete = { deleteSource = entry }
                        )
                    }
                }
            }

            if (restoreArchives.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Preserved restore archives",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "These contain the database files saved before or during a restore. Delete only when you no longer need that recovery point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2,
                        lineHeight = 18.sp
                    )
                    restoreArchives.forEach { archive ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface3)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.directory.name,
                                    color = OnSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                                Text(
                                    "${archive.sizeBytes / 1024} KB • ${if (archive.directory.name.startsWith("failed-")) "failed restore" else "pre-restore"}",
                                    color = OnSurface2,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(
                                onClick = { deleteArchive = archive },
                                enabled = !working && !pendingRestore,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete preserved recovery archive", tint = OnSurface2)
                            }
                        }
                    }
                }
            }
        }
    }

    restoreSource?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!working) restoreSource = null },
            containerColor = Surface2,
            titleContentColor = OnSurface,
            textContentColor = OnSurface2,
            title = { Text("Stage this restore?") },
            text = {
                Text(
                    "FocusFlow will use ${entry.file.name} (${entry.file.parent}) on the next launch. Before replacing the current database, it will create and verify a fresh safety backup and preserve the current database plus any WAL/SHM files. If the restored database fails initialization, FocusFlow will restore those preserved files. The source backup remains in place."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val selected = restoreSource
                        restoreSource = null
                        if (selected != null) stageRestore(selected)
                    },
                    enabled = !working
                ) { Text("Stage restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreSource = null }, enabled = !working) { Text("Cancel") }
            }
        )
    }

    deleteSource?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!working) deleteSource = null },
            containerColor = Surface2,
            titleContentColor = OnSurface,
            textContentColor = OnSurface2,
            title = {
                Text(
                    if (entry.location == BackupLocation.SECONDARY) {
                        "Delete this secondary backup?"
                    } else {
                        "Delete this safety backup?"
                    }
                )
            },
            text = {
                Text(
                    if (entry.location == BackupLocation.SECONDARY) {
                        "This removes only ${entry.file.name} and its matching FocusFlow hash/metadata sidecars from the selected secondary folder. It cannot be undone; no other files will be touched."
                    } else {
                        "This permanently removes ${entry.file.name} and its hash/metadata sidecars from FocusFlow's safety-backup folder. It reduces restore options; the active database is not changed."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (entry.location == BackupLocation.SECONDARY) {
                            deleteSecondaryBackup(entry)
                        } else {
                            deleteSafetyBackup(entry)
                        }
                    },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete copy") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSource = null }, enabled = !working) { Text("Keep backup") }
            }
        )
    }

    deleteArchive?.let { archive ->
        AlertDialog(
            onDismissRequest = { if (!working) deleteArchive = null },
            containerColor = Surface2,
            titleContentColor = OnSurface,
            textContentColor = OnSurface2,
            title = { Text("Delete this recovery archive?") },
            text = {
                Text(
                    "This permanently deletes ${archive.directory.name} and all database/WAL/SHM files stored inside it. If the files are needed for recovery, they cannot be recreated. The active database and other backups will not be touched."
                )
            },
            confirmButton = {
                Button(
                    onClick = { deleteRestoreArchive(archive) },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete archive") }
            },
            dismissButton = {
                TextButton(onClick = { deleteArchive = null }, enabled = !working) { Text("Keep archive") }
            }
        )
    }
}

private data class BackupRecoverySnapshot(
    val secondaryDirectory: File?,
    val backups: List<BackupEntry>,
    val pendingRestore: Boolean,
    val restoreArchives: List<RestoreArchiveEntry>
)

@Composable
private fun BackupEntryRow(
    entry: BackupEntry,
    isLatest: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val (verificationLabel, isVerified) = when (val verification = entry.verification) {
        is AutoBackupService.BackupVerification.Verified -> "Verified" to true
        AutoBackupService.BackupVerification.Unverified -> "Unverified — restore disabled" to false
        is AutoBackupService.BackupVerification.Invalid -> "Invalid — ${verification.reason}" to false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    entry.file.name,
                    modifier = Modifier.weight(1f),
                    color = OnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                if (isLatest) {
                    Surface(
                        color = Purple80.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "Latest",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            color = Purple80,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Text(
                "Created: ${formatBackupCreatedAt(entry.createdAtMillis)}",
                color = OnSurface2,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            Text(
                "${when {
                    entry.location == BackupLocation.SECONDARY -> "Secondary"
                    entry.file.name.startsWith("focusflow_safety_") -> "Safety copy"
                    else -> "App data"
                }} • $verificationLabel • ${entry.file.length() / 1024} KB",
                color = if (isVerified) Success else OnSurface2,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2
            )
        }
        if (isVerified) {
            OutlinedButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
        }
        if (entry.location == BackupLocation.SECONDARY || entry.file.name.startsWith("focusflow_safety_")) {
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (entry.location == BackupLocation.SECONDARY) {
                        "Delete secondary backup"
                    } else {
                        "Delete safety backup"
                    },
                    tint = OnSurface2
                )
            }
        }
    }
}

private fun formatBackupCreatedAt(timestampMillis: Long): String =
    runCatching {
        Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    }.getOrDefault("Unknown")

// ── CrashLogRow ─────────────────────────────────────────────────────────────────
@Composable
private fun CrashLogRow(
    file: File,
    isSelected: Boolean,
    onEmail: () -> Unit,
    onOpenFolder: () -> Unit,
    onPreview: () -> Unit
) {
    val sizeKb = file.length() / 1024L
    val fmt    = DecimalFormat("#,###")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Purple80.copy(alpha = 0.10f) else Surface3)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Description, contentDescription = null,
            tint = if (isSelected) Purple80 else OnSurface2, modifier = Modifier.size(20.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, color = OnSurface, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text("${fmt.format(sizeKb)} KB  •  ${file.parent}",
                color = OnSurface2, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, fontSize = 10.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ShortcutTooltip("Preview report") {
                IconButton(onClick = onPreview, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Visibility, contentDescription = "Preview",
                        tint = OnSurface2, modifier = Modifier.size(16.dp))
                }
            }
            ShortcutTooltip("Open containing folder") {
                IconButton(onClick = onOpenFolder, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Open folder",
                        tint = OnSurface2, modifier = Modifier.size(16.dp))
                }
            }
            Button(
                onClick = onEmail,
                colors  = ButtonDefaults.buttonColors(containerColor = Purple80),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Email Report", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── ContactRow ───────────────────────────────────────────────────────────────────
@Composable
private fun ContactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Purple80, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = OnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(sublabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = OnSurface2, modifier = Modifier.size(14.dp))
    }
}

// ── SectionCard ──────────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null, tint = Purple80, modifier = Modifier.size(18.dp))
            Text(title, color = OnSurface, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        HorizontalDivider(color = Surface3, thickness = 1.dp)
        content()
    }
}
