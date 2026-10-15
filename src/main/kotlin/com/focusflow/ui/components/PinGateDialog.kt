package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.SettingWriteException
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.GlobalPin
import com.focusflow.services.PinPolicy
import com.focusflow.services.PinState
import com.focusflow.services.PinVerificationResult
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PinGateStep { ENTER_PIN, FORGOT_CONFIRM, FORGOT_DONE }

/**
 * @param allowReset When true (default), the "Forgot PIN?" reset link is shown
 *   after 2 failed attempts. Set to false for any PIN gate shown inside an active
 *   kiosk / Focus Launcher session — resetting the GlobalPin mid-session bypasses
 *   hard-lock entirely and must not be possible from within the overlay.
 */
@Composable
fun PinGateDialog(
    title: String = "",
    subtitle: String = "",
    allowReset: Boolean = true,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val s           = LocalizationManager.strings
    val resolvedTitle    = title.ifEmpty { s.defPinRequired }
    val resolvedSubtitle = subtitle.ifEmpty { s.defEnterPin }

    var step        by remember { mutableStateOf(PinGateStep.ENTER_PIN) }
    var pin         by remember { mutableStateOf("") }
    var showPin     by remember { mutableStateOf(false) }
    var error       by remember { mutableStateOf(false) }
    var attempts    by remember { mutableStateOf(0) }
    var resetPhrase by remember { mutableStateOf("") }
    var resetWriteError by remember { mutableStateOf("") }
    var pinState     by remember { mutableStateOf<PinState?>(null) }
    val scope       = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        pinState = withContext(Dispatchers.IO) { GlobalPin.state() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (step == PinGateStep.FORGOT_CONFIRM) Warning.copy(alpha = 0.15f)
                            else Purple80.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (step == PinGateStep.FORGOT_CONFIRM) Icons.Default.Warning else Icons.Default.Lock,
                        contentDescription = null,
                        tint     = if (step == PinGateStep.FORGOT_CONFIRM) Warning else Purple80,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    when (step) {
                        PinGateStep.ENTER_PIN      -> resolvedTitle
                        PinGateStep.FORGOT_CONFIRM -> s.pinGateResetTitle
                        PinGateStep.FORGOT_DONE    -> s.pinGateClearedTitle
                    },
                    color = OnSurface, fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            when (step) {
                PinGateStep.ENTER_PIN -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            when (pinState) {
                                null -> "Reading PIN settings…"
                                PinState.UNKNOWN -> PinPolicy.SETTINGS_READ_ERROR
                                PinState.NOT_SET -> s.pinGateNoPinSet
                                PinState.SET -> resolvedSubtitle
                            },
                            color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (pinState == PinState.SET) {
                            OutlinedTextField(
                                value          = pin,
                                onValueChange  = { pin = it; error = false },
                                placeholder    = { Text(s.defPinRequired, color = OnSurface2) },
                                singleLine     = true,
                                visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon   = {
                                    IconButton(onClick = { showPin = !showPin }) {
                                        Icon(
                                            if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint     = OnSurface2,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                isError = error,
                                colors  = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = if (error) Error else Purple80,
                                    unfocusedBorderColor = if (error) Error else OnSurface2,
                                    errorBorderColor     = Error
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (error) {
                            Text(
                                if (attempts >= 3) s.pinGateIncorrectLong else s.pinGateIncorrect,
                                color = Error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (allowReset && attempts >= 2) {
                            TextButton(
                                onClick        = { step = PinGateStep.FORGOT_CONFIRM },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(s.pinGateForgot, color = Warning, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                            }
                        }
                    }
                }

                PinGateStep.FORGOT_CONFIRM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Warning.copy(alpha = 0.08f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp))
                            Text(s.pinGateResetWarning, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(s.pinGateTypeReset, color = OnSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value         = resetPhrase,
                            onValueChange = { resetPhrase = it },
                            placeholder   = { Text(s.pinGateResetPlaceholder, color = OnSurface2) },
                            singleLine    = true,
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Warning,
                                unfocusedBorderColor = OnSurface2
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (resetWriteError.isNotEmpty()) {
                            Text(resetWriteError, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                PinGateStep.FORGOT_DONE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(s.pinGateClearedBody, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                PinGateStep.ENTER_PIN -> {
                    Button(
                        onClick = {
                            scope.launch {
                                var currentState = pinState
                                if (currentState == null || currentState == PinState.UNKNOWN) {
                                    currentState = withContext(Dispatchers.IO) { GlobalPin.state() }
                                    pinState = currentState
                                }

                                when (currentState) {
                                    PinState.NOT_SET -> onSuccess()
                                    PinState.UNKNOWN,
                                    null -> Unit
                                    PinState.SET -> when (
                                        withContext(Dispatchers.IO) { GlobalPin.verifyResult(pin) }
                                    ) {
                                        PinVerificationResult.ACCEPTED -> onSuccess()
                                        PinVerificationResult.INCORRECT -> {
                                            error = true
                                            attempts++
                                            pin = ""
                                        }
                                        PinVerificationResult.UNAVAILABLE -> {
                                            pinState = PinState.UNKNOWN
                                            error = false
                                        }
                                    }
                                }
                            }
                        },
                        colors  = ButtonDefaults.buttonColors(containerColor = Purple80),
                        enabled = pinState != null &&
                            (pinState != PinState.SET || pin.isNotBlank())
                    ) { Text(s.pinGateConfirm) }
                }
                PinGateStep.FORGOT_CONFIRM -> {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { GlobalPin.resetWithoutPin() }
                                    resetWriteError = ""
                                    step = PinGateStep.FORGOT_DONE
                                } catch (failure: SettingWriteException) {
                                    resetWriteError = PinPolicy.SETTINGS_WRITE_ERROR
                                }
                            }
                        },
                        colors  = ButtonDefaults.buttonColors(containerColor = Warning),
                        enabled = resetPhrase.trim() == "RESET"
                    ) { Text(s.pinGateClearPin, color = Surface) }
                }
                PinGateStep.FORGOT_DONE -> {
                    Button(
                        onClick = onDismiss,
                        colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) { Text(s.btnDone) }
                }
            }
        },
        dismissButton = {
            when (step) {
                PinGateStep.ENTER_PIN -> {
                    TextButton(onClick = onDismiss) { Text(s.btnCancel, color = OnSurface2) }
                }
                PinGateStep.FORGOT_CONFIRM -> {
                    TextButton(onClick = { step = PinGateStep.ENTER_PIN; resetPhrase = "" }) {
                        Text(s.btnBack, color = OnSurface2)
                    }
                }
                PinGateStep.FORGOT_DONE -> {}
            }
        }
    )
}
