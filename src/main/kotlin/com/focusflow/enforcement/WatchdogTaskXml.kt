package com.focusflow.enforcement

/**
 * Task Scheduler XML for the two FocusFlow tasks. Pure text, so it is unit-tested.
 *
 * `schtasks /create /sc ... /tr ...` cannot set the power options, and its defaults are "do not
 * start on battery" and "stop if the laptop goes on battery". With those defaults the watchdog
 * silently stopped protecting FocusFlow whenever a laptop was unplugged, and every task was
 * capped at 72 hours. Registering from XML sets all of it explicitly.
 */
internal object WatchdogTaskXml {

    enum class Trigger(val executionTimeLimit: String) {
        /** Every 2 minutes, forever. A run takes about a second; 5 minutes means a hung run cannot block later ones. */
        EVERY_TWO_MINUTES("PT5M"),

        /** Once at every logon. */
        AT_LOGON("PT10M")
    }

    /** `DOMAIN\user`, or just the user name when the domain is unknown, or null when neither is known. */
    fun userId(domain: String?, user: String?): String? = when {
        user.isNullOrBlank() -> null
        domain.isNullOrBlank() -> user
        else -> "$domain\\$user"
    }

    /**
     * @param startBoundary local time as `yyyy-MM-ddTHH:mm:ss`
     * @param userId the user the task runs as, or null to let schtasks use the current user
     */
    fun build(
        description: String,
        trigger: Trigger,
        action: WatchdogScript.TaskAction,
        userId: String?,
        startBoundary: String
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-16"?>""")
        appendLine("""<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">""")
        appendLine("  <RegistrationInfo>")
        appendLine("    <Description>${escape(description)}</Description>")
        appendLine("  </RegistrationInfo>")
        appendLine("  <Triggers>")
        when (trigger) {
            Trigger.EVERY_TWO_MINUTES -> {
                appendLine("    <TimeTrigger>")
                appendLine("      <Repetition>")
                appendLine("        <Interval>PT2M</Interval>")
                appendLine("        <StopAtDurationEnd>false</StopAtDurationEnd>")
                appendLine("      </Repetition>")
                appendLine("      <StartBoundary>$startBoundary</StartBoundary>")
                appendLine("      <Enabled>true</Enabled>")
                appendLine("    </TimeTrigger>")
            }
            Trigger.AT_LOGON -> {
                appendLine("    <LogonTrigger>")
                appendLine("      <StartBoundary>$startBoundary</StartBoundary>")
                appendLine("      <Enabled>true</Enabled>")
                appendLine("    </LogonTrigger>")
            }
        }
        appendLine("  </Triggers>")
        appendLine("  <Principals>")
        appendLine("""    <Principal id="Author">""")
        if (!userId.isNullOrBlank()) appendLine("      <UserId>${escape(userId)}</UserId>")
        appendLine("      <LogonType>InteractiveToken</LogonType>")
        appendLine("      <RunLevel>LeastPrivilege</RunLevel>")
        appendLine("    </Principal>")
        appendLine("  </Principals>")
        appendLine("  <Settings>")
        appendLine("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>")
        appendLine("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>")
        appendLine("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>")
        appendLine("    <AllowHardTerminate>true</AllowHardTerminate>")
        appendLine("    <StartWhenAvailable>false</StartWhenAvailable>")
        appendLine("    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>")
        appendLine("    <IdleSettings>")
        appendLine("      <Duration>PT10M</Duration>")
        appendLine("      <WaitTimeout>PT1H</WaitTimeout>")
        appendLine("      <StopOnIdleEnd>true</StopOnIdleEnd>")
        appendLine("      <RestartOnIdle>false</RestartOnIdle>")
        appendLine("    </IdleSettings>")
        appendLine("    <AllowStartOnDemand>true</AllowStartOnDemand>")
        appendLine("    <Enabled>true</Enabled>")
        appendLine("    <Hidden>false</Hidden>")
        appendLine("    <RunOnlyIfIdle>false</RunOnlyIfIdle>")
        appendLine("    <WakeToRun>false</WakeToRun>")
        appendLine("    <ExecutionTimeLimit>${trigger.executionTimeLimit}</ExecutionTimeLimit>")
        appendLine("    <Priority>7</Priority>")
        appendLine("  </Settings>")
        appendLine("""  <Actions Context="Author">""")
        appendLine("    <Exec>")
        appendLine("      <Command>${escape(action.command)}</Command>")
        appendLine("      <Arguments>${escape(action.arguments)}</Arguments>")
        appendLine("    </Exec>")
        appendLine("  </Actions>")
        appendLine("</Task>")
    }

    /** schtasks reads the file as UTF-16 little-endian with a byte-order mark, matching the declaration. */
    fun encode(xml: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE)

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
