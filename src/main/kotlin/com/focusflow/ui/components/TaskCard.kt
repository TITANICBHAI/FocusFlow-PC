package com.focusflow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.models.Task
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.SeriesRole
import com.focusflow.services.TaskSeriesService
import com.focusflow.ui.theme.*
import java.time.LocalDate

@Composable
fun TaskCard(
    task: Task,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onStartFocus: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onDeleteAndStop: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val s = LocalizationManager.strings
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val priorityColorTarget = when (task.priority) {
        "high"   -> Error
        "medium" -> Warning
        else     -> Success
    }

    val isDone    = task.completed || task.skipped
    val isOverdue = !isDone && task.scheduledDate != null &&
                    task.scheduledDate.isBefore(LocalDate.now())

    val stripeColorTarget = when {
        isDone    -> OnSurface2.copy(alpha = 0.3f)
        isOverdue -> Error
        else      -> priorityColorTarget
    }
    val stripeColor by animateColorAsState(
        targetValue   = stripeColorTarget,
        animationSpec = tween(350),
        label         = "taskStripe"
    )
    val cardBg by animateColorAsState(
        targetValue   = if (isDone) Surface3.copy(alpha = 0.5f) else Surface3,
        animationSpec = tween(350),
        label         = "taskBg"
    )
    val textColor by animateColorAsState(
        targetValue   = if (isDone) OnSurface2 else OnSurface,
        animationSpec = tween(350),
        label         = "taskText"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp).height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(stripeColor)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = textColor
                )
                if (isOverdue) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(Error.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(s.tasksOverdue.lowercase(), style = MaterialTheme.typography.bodySmall,
                            color = Error, fontSize = 9.sp)
                    }
                }
                if (task.skipped) {
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(Warning.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(s.taskCardSkipped, style = MaterialTheme.typography.bodySmall, color = Warning, fontSize = 9.sp)
                    }
                }
                if (task.focusMode && !isDone) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Shield, null, tint = Purple80, modifier = Modifier.size(13.dp))
                }
            }
            if (task.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(task.description, style = MaterialTheme.typography.bodySmall, color = OnSurface2, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, null, tint = OnSurface2, modifier = Modifier.size(12.dp))
                Text("${task.durationMinutes}m", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                if (task.scheduledTime != null) {
                    Text("·", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    Text(task.scheduledTime, style = MaterialTheme.typography.bodySmall, color = Purple60)
                }
                if (task.recurring) {
                    Icon(Icons.Default.Repeat, null, tint = Purple60, modifier = Modifier.size(12.dp))
                    Text(task.recurringType ?: "recurring", style = MaterialTheme.typography.bodySmall, color = Purple60)
                }
            }
            if (task.tags.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    task.tags.take(4).forEach { tag ->
                        Box(modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Purple80.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text("#$tag", style = MaterialTheme.typography.bodySmall,
                                color = Purple60, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!isDone) {
                IconButton(onClick = onStartFocus, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.PlayArrow, null, tint = Purple80, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onComplete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                }
                if (onSkip != null) {
                    IconButton(onClick = onSkip, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.SkipNext, null, tint = Warning, modifier = Modifier.size(18.dp))
                    }
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                Icon(
                    if (task.completed) Icons.Default.CheckCircle else Icons.Default.SkipNext,
                    null,
                    tint     = if (task.completed) Success else Warning,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        val stopAndClose: (() -> Unit)? =
            if (onDeleteAndStop != null) ({ showDeleteConfirm = false; onDeleteAndStop() }) else null
        DeleteTaskDialog(
            task            = task,
            onDismiss       = { showDeleteConfirm = false },
            onDeleteOnly    = { showDeleteConfirm = false; onDelete() },
            onDeleteAndStop = stopAndClose
        )
    }
}

/**
 * Delete confirmation. A repeating task asks whether to delete only this task or to stop the
 * series as well; a plain task (or a caller that cannot stop a series, [onDeleteAndStop] = null)
 * gets the simple confirm it always had.
 */
@Composable
internal fun DeleteTaskDialog(
    task: Task,
    onDismiss: () -> Unit,
    onDeleteOnly: () -> Unit,
    onDeleteAndStop: (() -> Unit)?
) {
    val s = LocalizationManager.strings
    val role = TaskSeriesService.roleOf(task)
    val seriesAware = onDeleteAndStop != null && role != SeriesRole.NONE
    val body = when {
        !seriesAware                -> "\"${task.title}\" ${s.taskCardDeleteBody}"
        role == SeriesRole.TEMPLATE -> SeriesText.deleteOriginalBody(task.title)
        else                        -> SeriesText.deleteCopyBody(task.title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = { Text(s.taskCardDeleteTitle, color = Error) },
        text  = { Text(body, color = OnSurface2) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (seriesAware && role == SeriesRole.COPY) {
                    OutlinedButton(onClick = onDeleteOnly) { Text(SeriesText.deleteOnlyButton, color = OnSurface) }
                }
                Button(
                    onClick = { if (seriesAware) onDeleteAndStop?.invoke() else onDeleteOnly() },
                    colors  = ButtonDefaults.buttonColors(containerColor = Error)
                ) { Text(if (seriesAware) SeriesText.deleteAndStopButton else s.btnDelete) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.btnCancel, color = OnSurface2) }
        }
    )
}
