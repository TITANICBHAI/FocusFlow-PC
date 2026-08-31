package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.GlobalPin
import com.focusflow.services.PinPolicy
import com.focusflow.ui.theme.*

private enum class PinSetupStep { CHOOSE, SET_CUSTOM, SHOW_GENERATED }

@Composable
fun GlobalPinSetupDialog(
    onDismiss: () -> Unit,
    onComplete: () -> Unit = onDismiss
) {
    val s             = LocalizationManager.strings
    var step          by remember { mutableStateOf(PinSetupStep.CHOOSE) }
    var customPin     by remember { mutableStateOf("") }
    var confirmPin    by remember { mutableStateOf("") }
    var showPin       by remember { mutableStateOf(false) }
    var pinError      by remember { mutableStateOf("") }
    var generatedPin  by remember { mutableStateOf("") }
    var savedConfirm  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { },
        containerColor   = Surface2,
        shape            = RoundedCornerShape(24.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Purple80, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when (step) {
                        PinSetupStep.CHOOSE         -> s.pinSetupProtectTitle
                        PinSetupStep.SET_CUSTOM     -> s.pinSetupSetTitle
                        PinSetupStep.SHOW_GENERATED -> s.pinSetupSaveTitle
                    },
                    color      = OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 20.sp,
                    textAlign  = TextAlign.Center
                )
            }
        },
        text = {
            when (step) {
                PinSetupStep.CHOOSE -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            s.pinSetupChooseBody,
                            color     = OnSurface2,
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            Triple(Icons.Default.Edit,        s.pinSetupOwnLabel,  s.pinSetupOwnSub),
                            Triple(Icons.Default.AutoFixHigh, s.pinSetupAutoLabel, s.pinSetupAutoSub),
                        ).forEach { (icon, label, sub) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Surface3)
                                    .padding(14.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                        .background(Purple80.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = Purple80, modifier = Modifier.size(18.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, color = OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(sub,   color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                PinSetupStep.SET_CUSTOM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(s.pinSetupCustomBody, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value         = customPin,
                            onValueChange = { customPin = it.take(PinPolicy.MAX_LENGTH); pinError = "" },
                            label         = { Text(s.pinSetupNewPinLabel) },
                            singleLine    = true,
                            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon  = {
                                IconButton(onClick = { showPin = !showPin }) {
                                    Icon(if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                                }
                            },
                            isError = pinError.isNotEmpty(),
                            colors  = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value         = confirmPin,
                            onValueChange = { confirmPin = it; pinError = "" },
                            label         = { Text(s.pinSetupConfirmPinLabel) },
                            singleLine    = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError  = pinError.isNotEmpty(),
                            colors   = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinError.isNotEmpty()) {
                            Text(pinError, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                PinSetupStep.SHOW_GENERATED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(s.pinSetupAutoBody, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Surface3)
                                .border(2.dp, Purple80.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                generatedPin,
                                color         = Purple80,
                                fontWeight    = FontWeight.Bold,
                                fontSize      = 28.sp,
                                letterSpacing = 4.sp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Warning.copy(alpha = 0.08f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp))
                            Text(s.pinSetupWarning, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked         = savedConfirm,
                                onCheckedChange = { savedConfirm = it },
                                colors          = CheckboxDefaults.colors(checkedColor = Purple80)
                            )
                            Text(s.pinSetupSavedConfirm, color = OnSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                PinSetupStep.CHOOSE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { step = PinSetupStep.SET_CUSTOM },
                                colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
                            ) { Text(s.pinSetupSetMyOwn) }
                            Button(
                                onClick = {
                                    generatedPin = GlobalPin.autoGenerate()
                                    step = PinSetupStep.SHOW_GENERATED
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Surface3)
                            ) { Text(s.pinSetupAutoLabel, color = OnSurface) }
                        }
                        TextButton(onClick = { GlobalPin.setDeclined(); onDismiss() }) {
                            Text(s.pinSetupNoThanks, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                PinSetupStep.SET_CUSTOM -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { step = PinSetupStep.CHOOSE }) { Text(s.btnBack, color = OnSurface2) }
                        Button(
                            onClick = {
                                when {
                                     customPin.length < PinPolicy.MIN_LENGTH -> pinError = s.pinSetupMinChars
                                     customPin.length > PinPolicy.MAX_LENGTH -> pinError = "PIN must be at most ${PinPolicy.MAX_LENGTH} characters"
                                    customPin != confirmPin -> pinError = s.pinSetupNoMatch
                                    else -> { GlobalPin.set(customPin); onComplete() }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                        ) { Text(s.pinSetupSavePin) }
                    }
                }
                PinSetupStep.SHOW_GENERATED -> {
                    Button(
                        onClick  = onComplete,
                        enabled  = savedConfirm,
                        colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) { Text(s.btnDone) }
                }
            }
        },
        dismissButton = {}
    )
}
