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
/**
 * Reads the private switch the Windows Installer package passes when it asks
 * FocusFlow whether a removal may proceed (see packaging/windows/main.wxs).
 */
object UninstallCheckFlag {
    fun isRequested(args: Array<String>): Boolean =
        args.any { it.equals("--uninstall-check", ignoreCase = true) }
}
