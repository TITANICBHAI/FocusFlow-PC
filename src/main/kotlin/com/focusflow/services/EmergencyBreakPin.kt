package com.focusflow.services

import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import javax.swing.JPasswordField

/** Starting an Emergency Break needs the Global PIN. Fail-closed: an unreadable PIN state counts as "set". */
object EmergencyBreakPolicy {
    fun requiresPin(pinState: PinState): Boolean = pinState != PinState.NOT_SET
}

object EmergencyBreakPin {
    /** Returns true when the break may start. Ending a break never needs a PIN. */
    fun authorizeStart(): Boolean {
        if (!EmergencyBreakPolicy.requiresPin(GlobalPin.state())) return true
        if (GraphicsEnvironment.isHeadless()) return false
        val field = JPasswordField(14)
        val answer = JOptionPane.showConfirmDialog(
            null,
            arrayOf<Any>("Enter your Global PIN to start an Emergency Break.", field),
            "Emergency Break",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.OK_OPTION) return false
        val entered = String(field.password)
        return when (GlobalPin.verifyResult(entered)) {
            PinVerificationResult.ACCEPTED -> true
            PinVerificationResult.INCORRECT -> {
                JOptionPane.showMessageDialog(null, "Incorrect PIN. No Emergency Break was started.", "Emergency Break", JOptionPane.WARNING_MESSAGE)
                false
            }
            PinVerificationResult.UNAVAILABLE -> {
                JOptionPane.showMessageDialog(null, PinPolicy.SETTINGS_READ_ERROR, "Emergency Break", JOptionPane.WARNING_MESSAGE)
                false
            }
        }
    }
}
