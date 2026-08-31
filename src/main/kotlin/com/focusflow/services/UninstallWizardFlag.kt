package com.focusflow.services

/**
 * Reads the private command-line switch used by the Windows uninstall entry.
 *
 * Keeping this separate from the Compose startup code also makes the intent
 * explicit: normal launches must never enter the uninstall wizard.
 */
object UninstallWizardFlag {
    fun isRequested(args: Array<String>): Boolean =
        args.any { it.equals("--uninstall", ignoreCase = true) }
}