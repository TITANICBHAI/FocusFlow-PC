package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.SettingReadException
import com.focusflow.data.requireSuccess
import java.util.logging.Level
import java.util.logging.Logger

/**
 * VpnBlocker
 *
 * Detects and kills known VPN processes when enforcement is active.
 * Prevents users from using a VPN to bypass Windows Firewall rules.
 *
 * Built-in list covers the most popular VPN clients. Users can also add
 * custom process names via addCustomProcess().
 */
object VpnBlocker {
    private val logger = Logger.getLogger(VpnBlocker::class.java.name)
    private const val ENABLED_KEY = "vpn_block_enabled"
    private const val CUSTOM_PROCESSES_KEY = "vpn_custom_processes"

    val KNOWN_VPN_PROCESSES: Set<String> = setOf(
        // NordVPN
        "nordvpn.exe", "nordvpn-service.exe",
        // ExpressVPN
        "expressvpn.exe", "expressvpnservice.exe", "expressvpnlauncher.exe",
        // ProtonVPN
        "protonvpn.exe", "protonvpn-service.exe",
        // Windscribe
        "windscribe.exe", "windscribeservice.exe",
        // CyberGhost
        "cyberghost.exe", "cyberghost64.exe", "cyberghostservice.exe",
        // Surfshark
        "surfshark.exe", "surfshark-service.exe",
        // Private Internet Access
        "pia.exe", "pia-service.exe",
        // Mullvad
        "mullvad.exe", "mullvad-daemon.exe",
        // IPVanish
        "ipvanish.exe",
        // HMA (HideMyAss)
        "hidemyass.exe", "hmavpn.exe",
        // TunnelBear
        "tunnelbear.exe",
        // Hotspot Shield
        "hotspotshield.exe", "hotspotshield-service.exe",
        // Avast SecureLine
        "avastsvpn.exe",
        // AVG Secure VPN
        "avgsecu.exe",
        // VyprVPN
        "vyprvpn.exe",
        // PrivateVPN
        "privatevpn.exe",
        // PureVPN
        "purevpn.exe",
        // ZenMate
        "zenmate.exe",
        // TorGuard
        "torguard.exe",
        // Lantern
        "lantern.exe",
        // Psiphon
        "psiphon3.exe",
        // UltraSurf
        "ultrasurf.exe",
        // OpenVPN (generic)
        "openvpn.exe", "openvpn-gui.exe",
        // WireGuard (generic + Windows service — most common install method)
        "wireguard.exe", "wireguard-service.exe", "wgservice.exe",
        // Cisco AnyConnect
        "anyconnect.exe", "vpnui.exe", "vpnagent.exe", "csclient.exe",
        // Cisco Umbrella (DNS-layer VPN/proxy)
        "umbrella.exe", "umbrellad.exe", "ciscoumbrella.exe",
        // Palo Alto GlobalProtect
        "globalprotect.exe", "pangpa.exe", "pangps.exe",
        // Fortinet FortiClient
        "fortisslvpn.exe", "forticlient.exe", "forticlientvpn.exe",
        // Pulse Secure / Ivanti
        "pulsesecure.exe", "pulsesvc.exe", "ivanti.exe",
        // Zscaler
        "zscalertunnel.exe", "zscaler.exe", "zscalerclient.exe", "zscalerservice.exe",
        // F5 VPN (BIG-IP Edge)
        "f5vpn.exe", "f5fpc.exe",
        // SoftEther
        "softethervpn.exe", "vpnclient.exe",
        // IVPN
        "ivpn.exe", "ivpnservice.exe",
        // SaferVPN / CactusVPN / other smaller clients
        "safervpn.exe", "cactusvpn.exe",
        // Windows built-in VPN dialer (rasphone / rasdial can create/connect VPN tunnels)
        "rasphone.exe", "rasdial.exe",
        // Tor
        "tor.exe"
    )

    // ── Hot-path caches — never hit the DB on the WinEventHook foreground callback ──

    /** Null = not loaded yet; populated by the background refresh or management UI. */
    @Volatile private var _cachedEnabled: Boolean? = null

    /** Null = not loaded yet; populated by the background refresh or management UI. */
    @Volatile private var _cachedCustomProcesses: List<String>? = null

    /**
     * Enforcement callers use this cache-only view. Unknown enable state fails
     * closed, so the foreground hook never has to read SQLite.
     */
    internal val isEnabledForEnforcement: Boolean
        get() = _cachedEnabled ?: true

    var isEnabled: Boolean
        @Synchronized
        get() = _cachedEnabled ?: when (val stored = Database.readSetting(ENABLED_KEY)) {
            is Database.SettingRead.Present ->
                (stored.value == "true").also { _cachedEnabled = it }
            Database.SettingRead.Absent -> false.also { _cachedEnabled = it }
            is Database.SettingRead.Unreadable -> {
                logger.log(
                    Level.WARNING,
                    "VPN enable state is unreadable; treating protection as enabled without caching the fallback",
                    stored.cause
                )
                true
            }
        }
        @Synchronized
        set(value) {
            Database.writeSetting(ENABLED_KEY, if (value) "true" else "false")
                .requireSuccess("update VPN blocker state")
            _cachedEnabled = value
        }

    /**
     * Refresh persisted VPN settings from a background thread. Failed reads do
     * not replace a last-known-good cache; an unknown enable value remains
     * fail-closed through [isEnabledForEnforcement].
     */
    @Synchronized
    internal fun refreshSettingsFromDatabase() {
        when (val stored = Database.readSetting(ENABLED_KEY)) {
            is Database.SettingRead.Present -> _cachedEnabled = stored.value == "true"
            Database.SettingRead.Absent -> _cachedEnabled = false
            is Database.SettingRead.Unreadable -> logger.log(
                Level.WARNING,
                "VPN enable state refresh failed; retaining the last known value",
                stored.cause
            )
        }

        when (val stored = Database.readSetting(CUSTOM_PROCESSES_KEY)) {
            is Database.SettingRead.Present ->
                _cachedCustomProcesses = parseCustomProcesses(stored.value)
            Database.SettingRead.Absent -> _cachedCustomProcesses = emptyList()
            is Database.SettingRead.Unreadable -> logger.log(
                Level.WARNING,
                "VPN custom process refresh failed; retaining the last known list",
                stored.cause
            )
        }
    }

    @Synchronized
    fun getCustomProcesses(): List<String> {
        _cachedCustomProcesses?.let { return it }
        return when (val stored = Database.readSetting(CUSTOM_PROCESSES_KEY)) {
            is Database.SettingRead.Present ->
                parseCustomProcesses(stored.value).also { _cachedCustomProcesses = it }
            Database.SettingRead.Absent -> emptyList<String>().also { _cachedCustomProcesses = it }
            is Database.SettingRead.Unreadable -> {
                logger.log(
                    Level.WARNING,
                    "VPN custom process list is unreadable; leaving it uncached so a later read can recover",
                    stored.cause
                )
                emptyList()
            }
        }
    }

    @Synchronized
    fun addCustomProcess(processName: String) {
        val lower = processName.trim().lowercase().let {
            if (!it.endsWith(".exe")) "$it.exe" else it
        }
        val existing = customProcessesForMutation().toMutableList()
        if (!existing.contains(lower)) {
            existing.add(lower)
            Database.writeSetting(CUSTOM_PROCESSES_KEY, existing.joinToString(","))
                .requireSuccess("add VPN process")
            _cachedCustomProcesses = existing          // keep cache in sync
        }
    }

    @Synchronized
    fun removeCustomProcess(processName: String) {
        val lower = processName.trim().lowercase()
        val updated = customProcessesForMutation().filter { it != lower }
        Database.writeSetting(CUSTOM_PROCESSES_KEY, updated.joinToString(","))
            .requireSuccess("remove VPN process")
        _cachedCustomProcesses = updated              // keep cache in sync
    }

    fun getAllBlockedProcesses(): Set<String> =
        KNOWN_VPN_PROCESSES + _cachedCustomProcesses.orEmpty().toSet()

    fun isVpnProcess(processName: String): Boolean {
        if (!isEnabledForEnforcement) return false
        val lower = processName.lowercase()
        return KNOWN_VPN_PROCESSES.contains(lower) ||
            _cachedCustomProcesses.orEmpty().contains(lower)
    }

    private fun customProcessesForMutation(): List<String> {
        _cachedCustomProcesses?.let { return it }
        return when (val stored = Database.readSetting(CUSTOM_PROCESSES_KEY)) {
            is Database.SettingRead.Present ->
                parseCustomProcesses(stored.value).also { _cachedCustomProcesses = it }
            Database.SettingRead.Absent -> emptyList<String>().also { _cachedCustomProcesses = it }
            is Database.SettingRead.Unreadable -> {
                logger.log(
                    Level.WARNING,
                    "Refusing to change VPN custom processes because their persisted state is unreadable",
                    stored.cause
                )
                throw SettingReadException("VPN custom process list", stored.cause)
            }
        }
    }

    private fun parseCustomProcesses(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    @Synchronized
    internal fun resetForTest() {
        _cachedEnabled = null
        _cachedCustomProcesses = null
    }
}
