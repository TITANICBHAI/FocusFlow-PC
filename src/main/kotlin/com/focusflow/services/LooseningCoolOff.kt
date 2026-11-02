package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor

/**
 * Optional 10-minute cool-off before a protection is turned OFF, once its PIN was entered.
 * One switch per PIN in Settings; both are OFF by default, so nothing changes until the owner
 * turns one on. The switches themselves are cooled too: turning a switch off after its PIN check
 * also waits 10 minutes, otherwise the cool-off could be skipped by switching it off first.
 *
 * Asking again while a cool-off is running cancels it (after the same PIN check).
 */
object LooseningCoolOff {
    const val GLOBAL_SETTING = "cooloff_global_pin"
    const val NUCLEAR_SETTING = "cooloff_nuclear_pin"
    const val KEY_NUCLEAR_OFF = "nuclear_off"
    const val KEY_ALWAYS_ON_OFF = "always_on_off"
    const val DELAY_MS = 10 * 60 * 1000L

    private val scheduler = CoolOffScheduler()

    fun isEnabled(settingKey: String): Boolean =
        runCatching { Database.getSetting(settingKey) == "true" }.getOrDefault(false)

    fun setEnabled(settingKey: String, enabled: Boolean) {
        runCatching { Database.setSetting(settingKey, enabled.toString()) }
    }

    fun isPending(key: String): Boolean = scheduler.isPending(key)

    fun cancel(key: String): Boolean = scheduler.cancel(key)

    /**
     * Runs [applyNow] at once when the switch [settingKey] is off. Otherwise starts the cool-off
     * (or cancels a running one). Returns true only when [applyNow] ran now.
     */
    fun requestLoosening(
        settingKey: String,
        pendingKey: String,
        what: String,
        applyNow: () -> Unit
    ): Boolean {
        if (!isEnabled(settingKey)) {
            applyNow()
            return true
        }
        if (scheduler.isPending(pendingKey)) {
            scheduler.cancel(pendingKey)
            notify("Cool-off cancelled", "$what stays on.")
            return false
        }
        scheduler.schedule(pendingKey, DELAY_MS) {
            applyNow()
            notify("Cool-off finished", "$what is now off.")
        }
        notify(
            "Cool-off started",
            "$what will turn off in 10 minutes. Ask to turn it off again to cancel."
        )
        return false
    }

    /** Always-On enforcement, guarded by the Global PIN. Call from a background thread. */
    fun requestAlwaysOnOff(): Boolean =
        requestLoosening(GLOBAL_SETTING, KEY_ALWAYS_ON_OFF, "Always-On enforcement") {
            ProcessMonitor.alwaysOnEnabled = false
            runCatching { Database.setSetting("always_on_enforcement", "false") }
        }

    /** Nuclear Mode, guarded by the Nuclear PIN. */
    fun requestNuclearOff(): Boolean {
        if (!NuclearMode.isActive) return true
        return requestLoosening(NUCLEAR_SETTING, KEY_NUCLEAR_OFF, "Nuclear Mode") { NuclearMode.disable() }
    }

    /** Turning one of the two cool-off switches off. Returns true when it is off now. */
    fun requestSwitchOff(settingKey: String): Boolean =
        requestLoosening(settingKey, "switch:$settingKey", "The cool-off setting") {
            setEnabled(settingKey, false)
        }

    private fun notify(title: String, message: String) {
        runCatching { SystemTrayManager.showNotification(title, message) }
    }
}
