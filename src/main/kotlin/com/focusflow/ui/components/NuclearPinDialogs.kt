package com.focusflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.data.SettingWriteException
import com.focusflow.services.NuclearPin
import com.focusflow.services.PinPolicy
import com.focusflow.services.PinVerificationResult
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Nuclear Mode PIN gate dialog ──────────────────────────────────────────────
// Shown when the user tries to toggle Nuclear Mode OFF and a PIN is set.
// Verifies against NuclearPin (independent of GlobalPin / SessionPin).
@Composable
fun NuclearPinGateDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Error, modifier = Modifier.size(22.dp))
                Text("Nuclear Mode PIN", color = OnSurface, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your Nuclear Mode PIN to disable enforcement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                OutlinedTextField(
                    value         = pin,
                    onValueChange = { pin = it; error = "" },
                    label         = { Text("Nuclear Mode PIN") },
                    singleLine    = true,
                    isError       = error.isNotEmpty(),
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple80,
                        unfocusedBorderColor = OnSurface2,
                        errorBorderColor     = Error
                    )
                )
                if (error.isNotEmpty()) {
                    Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        when (withContext(Dispatchers.IO) { NuclearPin.verifyResult(pin) }) {
                            PinVerificationResult.ACCEPTED -> onVerified()
                            PinVerificationResult.INCORRECT -> {
                                error = "Incorrect PIN."
                                pin = ""
                            }
                            PinVerificationResult.UNAVAILABLE ->
                                error = PinPolicy.SETTINGS_READ_ERROR
                        }
                    }
                },
                enabled = pin.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurface2) }
        }
    )
}

// ── Nuclear Mode PIN setup dialog ─────────────────────────────────────────────
// Set a new 4-char PIN, change it (verify current first), or clear it.
@Composable
fun NuclearPinSetupDialog(pinAlreadySet: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    // step 0 = verify current PIN (only when changing/clearing an existing PIN)
    // step 1 = enter new PIN
    var step       by remember { mutableStateOf(if (pinAlreadySet) 0 else 1) }
    var currentPin by remember { mutableStateOf("") }
    var newPin     by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var clearing   by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf("") }
    val scope      = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(22.dp))
                Text(
                    when {
                        step == 0              -> "Verify Current PIN"
                        clearing               -> "Clear Nuclear Mode PIN"
                        else                   -> if (pinAlreadySet) "Set New PIN" else "Set Nuclear Mode PIN"
                    },
                    color = OnSurface, fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    // Step 0: verify the existing PIN before allowing change/clear
                    0 -> {
                        Text(
                            "Enter your current Nuclear Mode PIN to continue.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurface2
                        )
                        OutlinedTextField(
                            value         = currentPin,
                            onValueChange = { currentPin = it; error = "" },
                            label         = { Text("Current PIN") },
                            singleLine    = true,
                            isError       = error.isNotEmpty(),
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Purple80,
                                unfocusedBorderColor = OnSurface2,
                                errorBorderColor     = Error
                            )
                        )
                        if (error.isNotEmpty()) Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
                        // Offer a "Clear PIN" path from this screen
                        TextButton(
                            onClick        = { clearing = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Remove PIN instead", color = Warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // Step 1: enter new PIN (or confirm clear)
                    1 -> {
                        if (clearing) {
                            Text(
                                "Confirm you want to remove the Nuclear Mode PIN. Nuclear Mode will be togglable freely after this.",
                                style = MaterialTheme.typography.bodySmall, color = OnSurface2
                            )
                        } else {
                            Text(
                                "Choose a PIN (minimum 4 characters). You'll need it to turn Nuclear Mode off.",
                                style = MaterialTheme.typography.bodySmall, color = OnSurface2
                            )
                            OutlinedTextField(
                                value         = newPin,
                                onValueChange = { newPin = it; error = "" },
                                label         = { Text("New PIN (min 4 chars)") },
                                singleLine    = true,
                                isError       = error.isNotEmpty() || (newPin.isNotBlank() && newPin.length < 4),
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Purple80,
                                    unfocusedBorderColor = OnSurface2,
                                    errorBorderColor     = Error
                                )
                            )
                            OutlinedTextField(
                                value         = confirmPin,
                                onValueChange = { confirmPin = it; error = "" },
                                label         = { Text("Confirm PIN") },
                                singleLine    = true,
                                isError       = error.isNotEmpty(),
                                modifier      = Modifier.fillMaxWidth(),
                                colors        = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Purple80,
                                    unfocusedBorderColor = OnSurface2,
                                    errorBorderColor     = Error
                                )
                            )
                            if (error.isNotEmpty()) Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        try {
                        when (step) {
                            0 -> {
                                when (withContext(Dispatchers.IO) { NuclearPin.verifyResult(currentPin) }) {
                                    PinVerificationResult.ACCEPTED -> { step = 1; error = "" }
                                    PinVerificationResult.INCORRECT -> error = "Incorrect PIN."
                                    PinVerificationResult.UNAVAILABLE ->
                                        error = PinPolicy.SETTINGS_READ_ERROR
                                }
                            }
                            1 -> {
                                if (clearing) {
                                    withContext(Dispatchers.IO) { NuclearPin.clearForced() }
                                    onChanged()
                                } else {
                                    when {
                                        newPin.length < 4      -> error = "PIN must be at least 4 characters."
                                        newPin != confirmPin   -> error = "PINs do not match."
                                        else -> {
                                            withContext(Dispatchers.IO) { NuclearPin.set(newPin) }
                                            onChanged()
                                        }
                                    }
                                }
                            }
                        }
                        } catch (failure: SettingWriteException) {
                            error = PinPolicy.SETTINGS_WRITE_ERROR
                        }
                    }
                },
                enabled = when (step) {
                    0    -> currentPin.isNotBlank()
                    else -> if (clearing) true else (newPin.length >= 4 && confirmPin.isNotBlank())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (clearing && step == 1) Error else Purple80
                )
            ) {
                Text(
                    when {
                        step == 0 -> "Next"
                        clearing  -> "Remove PIN"
                        else      -> "Save PIN"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = if (step == 1 && pinAlreadySet) {{ step = 0; clearing = false; error = "" }}
                                  else onDismiss) {
                Text(if (step == 1 && pinAlreadySet) "Back" else "Cancel", color = OnSurface2)
            }
        }
    )
}
