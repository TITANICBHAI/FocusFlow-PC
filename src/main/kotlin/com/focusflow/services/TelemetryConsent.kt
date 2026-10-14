package com.focusflow.services

import com.focusflow.data.Database
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal enum class TelemetryConsentState {
    UNKNOWN,
    GRANTED,
    DENIED,
    UNREADABLE,
}

/**
 * File-backed, fail-closed crash-report consent. Reading or writing consent
 * never requires the FocusFlow database.
 */
internal object TelemetryConsent {
    const val LEGACY_SETTING_KEY = "crash_reports_enabled"

    private const val CONSENT_FILE = "telemetry-consent"
    private const val MIGRATION_FILE = "telemetry-consent.migrated"
    private const val MIGRATION_VERSION = "v1"
    private val lock = Any()

    fun state(): TelemetryConsentState = synchronized(lock) {
        val path = try {
            consentPath()
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            return@synchronized TelemetryConsentState.UNREADABLE
        }

        val contents = try {
            Files.readString(path).trim()
        } catch (_: NoSuchFileException) {
            return@synchronized TelemetryConsentState.UNKNOWN
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            return@synchronized TelemetryConsentState.UNREADABLE
        }

        when (contents) {
            "granted" -> TelemetryConsentState.GRANTED
            "denied" -> TelemetryConsentState.DENIED
            else -> TelemetryConsentState.UNREADABLE
        }
    }

    fun isGranted(): Boolean = state() == TelemetryConsentState.GRANTED

    fun save(state: TelemetryConsentState): Boolean {
        if (state != TelemetryConsentState.GRANTED && state != TelemetryConsentState.DENIED) {
            return false
        }
        return synchronized(lock) {
            try {
                writeAtomically(consentPath(), state.name.lowercase())
                true
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Imports the previous database setting only when the new consent file is
     * absent and the database read completed successfully. Missing legacy
     * settings are recorded as migrated but remain UNKNOWN (no consent).
     */
    fun migrateLegacyOnce(legacy: Database.SettingReadResult): Boolean = synchronized(lock) {
        val current = state()
        if (current == TelemetryConsentState.UNREADABLE) return@synchronized false

        val migrationMarker = try {
            migrationPath()
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            return@synchronized false
        }

        when (readMigrationMarker(migrationMarker)) {
            MarkerState.COMPLETE -> return@synchronized true
            MarkerState.UNREADABLE -> return@synchronized false
            MarkerState.MISSING -> Unit
        }

        // A valid new-format choice always takes precedence over old DB data.
        if (current == TelemetryConsentState.GRANTED || current == TelemetryConsentState.DENIED) {
            return@synchronized writeMarker(migrationMarker)
        }

        val migratedState = when (legacy) {
            is Database.SettingReadResult.Found -> when (legacy.value.trim().lowercase()) {
                "true" -> TelemetryConsentState.GRANTED
                "false" -> TelemetryConsentState.DENIED
                else -> TelemetryConsentState.UNKNOWN
            }
            Database.SettingReadResult.Missing -> TelemetryConsentState.UNKNOWN
            Database.SettingReadResult.NotReady,
            is Database.SettingReadResult.Unreadable -> return@synchronized false
        }

        if (migratedState != TelemetryConsentState.UNKNOWN) {
            try {
                writeAtomically(consentPath(), migratedState.name.lowercase())
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (_: Throwable) {
                return@synchronized false
            }
        }
        writeMarker(migrationMarker)
    }

    private enum class MarkerState { MISSING, COMPLETE, UNREADABLE }

    private fun readMigrationMarker(path: Path): MarkerState = try {
        if (Files.readString(path).trim() == MIGRATION_VERSION) MarkerState.COMPLETE
        else MarkerState.UNREADABLE
    } catch (_: NoSuchFileException) {
        MarkerState.MISSING
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (_: Throwable) {
        MarkerState.UNREADABLE
    }

    private fun writeMarker(path: Path): Boolean = try {
        writeAtomically(path, MIGRATION_VERSION)
        true
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (_: Throwable) {
        false
    }

    private fun consentPath(): Path = profileDirectory().resolve(CONSENT_FILE)

    private fun migrationPath(): Path = profileDirectory().resolve(MIGRATION_FILE)

    private fun profileDirectory(): Path {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?: throw IOException("User home is unavailable")
        return Path.of(home).resolve(".focusflow")
    }

    private fun writeAtomically(destination: Path, contents: String) {
        val directory = destination.parent ?: throw IOException("Consent path has no parent")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".telemetry-consent-", ".tmp")
        try {
            Files.writeString(
                temporary,
                contents,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
